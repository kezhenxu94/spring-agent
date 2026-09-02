package me.kezhenxu94.springagent.rag.milvus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeDocument;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeEntry;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeMetadata;
import me.kezhenxu94.springagent.core.knowledge.KnowledgePage;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScopeFilter;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.milvus.MilvusFilterExpressionConverter;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.FileSystemResource;

/**
 * A knowledge base kept in a Milvus collection of its own.
 *
 * <p>The store and its client are private fields rather than beans, which is the whole reason this
 * class exists in the shape it does. Spring AI's Milvus auto-configuration declares its {@code
 * MilvusVectorStore} {@code @ConditionalOnMissingBean}, so publishing one here would make that one
 * back off and silently take the tool search index's store with it — no error, just worse tool
 * search. Keeping the store inside means the bean factory has exactly one {@code VectorStore} for
 * exactly the purpose it always had.
 *
 * <p>Enumeration is the part no portable vector-store interface offers, and the reason a knowledge
 * base is a backend rather than something core could implement over any {@code VectorStore}: {@link
 * #list} drops to the Milvus client directly. What it does not do is invent a second definition of
 * who may read what — {@link KnowledgeScopeFilter} builds one expression and {@link
 * MilvusFilterExpressionConverter} renders it for the raw query, so the scoping used to search and
 * the scoping used to list cannot drift apart.
 */
@Slf4j
public class MilvusKnowledgeBase implements KnowledgeBase, InitializingBean, DisposableBean {

  /**
   * Milvus returns document metadata as a JSON blob under this field. It is Spring AI's default
   * name for it, restated because the raw query has to ask for the field by name and the store
   * keeps its own copy private.
   */
  private static final String METADATA_FIELD = "metadata";

  /** The chunk's own text, likewise Spring AI's default name for the column it writes it to. */
  private static final String CONTENT_FIELD = "content";

  /**
   * How many chunks of one document a move will rewrite. A document is fetched whole to be moved,
   * so this is the point at which that stops being reasonable; well past anything the splitter
   * produces from a page, a ticket or a file, and far below the limit Milvus puts on a query.
   */
  private static final int MAX_CHUNKS_PER_DOCUMENT = 4096;

  private final MilvusKnowledgeProperties properties;
  private final SpringAgentProperties agentProperties;
  private final MilvusServiceClient client;
  private final MilvusVectorStore store;
  private final MilvusFilterExpressionConverter toMilvus = new MilvusFilterExpressionConverter();

  public MilvusKnowledgeBase(
      final MilvusKnowledgeProperties properties,
      final SpringAgentProperties agentProperties,
      final EmbeddingModel embeddingModel) {
    this.properties = properties;
    this.agentProperties = agentProperties;
    try {
      this.client =
          new MilvusServiceClient(
              ConnectParam.newBuilder()
                  .withHost(properties.host())
                  .withPort(properties.port())
                  .build());
    } catch (RuntimeException e) {
      // The SDK's own failure is a bare gRPC DEADLINE_EXCEEDED naming neither the host it tried
      // nor the setting that sent it there, which is a long way from the two things anyone
      // debugging this needs. Failing at startup is right — an enabled knowledge base with no
      // database is a misconfiguration, not a degraded mode — but it should say what to fix.
      throw new IllegalStateException(
          "Cannot reach the knowledge base Milvus at "
              + properties.host()
              + ":"
              + properties.port()
              + ". Start it, correct app.ai.rag.milvus.host and .port, or set app.ai.rag.enabled"
              + " to false to run without a knowledge base.",
          e);
    }
    this.store =
        MilvusVectorStore.builder(client, embeddingModel)
            .collectionName(properties.collectionName())
            .embeddingDimension(properties.embeddingDimension())
            .initializeSchema(properties.initializeSchema())
            .build();
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    // The store is not a bean, so nothing else will run its lifecycle for it. This is what creates
    // the collection and its index on a first start.
    store.afterPropertiesSet();
    // And this is what makes querying it work on every start after that. Milvus answers a query
    // only against a loaded collection, and the store loads it as part of *creating* it — so a
    // restart against an existing collection would otherwise list nothing at all, looking exactly
    // like an empty knowledge base rather than an error. Loading twice is harmless.
    client.loadCollection(
        LoadCollectionParam.newBuilder().withCollectionName(properties.collectionName()).build());
  }

  @Override
  public void destroy() {
    client.close();
  }

  @Override
  public String index(final KnowledgeSource source) {
    final var text = source.mustBeRead() ? extractText(source.source()) : source.text();
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Nothing to index: the content is empty");
    }

    final var owning = source.owningScope();
    final var docId = source.docId();
    // Every index is a replacement, because every document is indexed under an id its caller chose
    // — an id naming nothing yet simply deletes nothing and this stores the first copy.
    //
    // Delete before adding, not after: the new document has a different number of chunks than the
    // old one whenever the text changed, so there is no chunk-by-chunk overwrite to rely on and
    // anything left behind would go on matching searches alongside its own replacement.
    //
    // Scoped to the scope being written rather than to everything the requester can read. A user
    // who may read the company knowledge base must not be able to destroy a document in it by
    // re-indexing that id as their own; here a mismatched id matches nothing, so the write quietly
    // becomes a document of their own instead of somebody else's loss.
    store.delete(KnowledgeScopeFilter.documentOwnedBy(owning, docId));

    final var splitter =
        TokenTextSplitter.builder().withChunkSize(agentProperties.ai().rag().chunkSize()).build();
    var chunks = splitter.split(new Document(text));
    if (chunks.isEmpty()) {
      // The splitter drops anything shorter than its minimum embeddable length, so a short note —
      // an address, a URL, a one-line convention — splits into nothing, and Milvus then rejects the
      // empty insert with a message about auto-id fields that says nothing about the real cause.
      // Short knowledge is still knowledge; store it as the single chunk it already is.
      chunks = List.of(new Document(text));
    }

    final var createdAt = Instant.now().toString();
    final var stamped = new ArrayList<Document>(chunks.size());
    for (var i = 0; i < chunks.size(); i++) {
      final var metadata = new LinkedHashMap<String, Object>();
      // All three scope fields, always, blank where they do not apply. A missing key and a blank
      // one are not the same to a filter, and only one of them is worth reasoning about.
      metadata.put(KnowledgeMetadata.OWNER, owning.owner());
      metadata.put(KnowledgeMetadata.GROUP, owning.group());
      metadata.put(KnowledgeMetadata.TENANT, owning.tenant());
      metadata.put(KnowledgeMetadata.DOC_ID, docId);
      metadata.put(KnowledgeMetadata.TITLE, source.title());
      metadata.put(KnowledgeMetadata.SOURCE, source.attribution());
      metadata.put(KnowledgeMetadata.CREATED_AT, createdAt);
      metadata.put(KnowledgeMetadata.CHUNK, i);
      // Repeated on every chunk although only the zero-th is ever read back for it — see
      // KnowledgeMetadata.CHUNK_COUNT for why listing cannot count the chunks it does not fetch.
      metadata.put(KnowledgeMetadata.CHUNK_COUNT, chunks.size());
      stamped.add(new Document(chunks.get(i).getText(), metadata));
    }

    store.add(stamped);
    log.debug("Indexed {} into {} chunks as {}", source.title(), stamped.size(), docId);
    return docId;
  }

  @Override
  public KnowledgePage list(final KnowledgeScope scope, final int offset, final int limit) {
    // One row beyond the page, so that "are there more" costs nothing extra. Milvus has no cheap
    // count, and a second query for one boolean would double the cost of every listing.
    final var probe = limit + 1;
    final var expression = toMilvus.convertExpression(KnowledgeScopeFilter.firstChunks(scope));

    final var response =
        client.query(
            QueryParam.newBuilder()
                .withCollectionName(properties.collectionName())
                .withExpr(expression)
                .withOutFields(List.of(METADATA_FIELD))
                .withOffset((long) offset)
                .withLimit((long) probe)
                // Milvus is eventually consistent by default, and a listing is almost always read
                // right after a write — the model stores a document and then shows the user what is
                // there. Under the default level that read can legitimately miss what was just
                // indexed, which reads as the write having silently failed. Listings are rare and
                // small compared with searches, so the stronger guarantee is worth its latency
                // here in a way it would not be on the retrieval path.
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .build());
    if (response.getData() == null) {
      throw new IllegalStateException("Milvus refused the listing query: " + response.getMessage());
    }

    final List<?> rows;
    try {
      rows =
          new QueryResultsWrapper(response.getData())
              .getFieldWrapper(METADATA_FIELD)
              .getFieldData();
    } catch (Exception e) {
      throw new IllegalStateException("Could not read the listing from Milvus", e);
    }

    final var entries = new ArrayList<KnowledgeEntry>();
    for (final var row : rows.stream().limit(limit).toList()) {
      entries.add(toEntry(row));
    }
    return new KnowledgePage(List.copyOf(entries), rows.size() > limit);
  }

  @Override
  public Optional<KnowledgeDocument> read(final KnowledgeScope scope, final String docId) {
    final var chunks = documentChunks(scope, docId);
    if (chunks.isEmpty()) {
      return Optional.empty();
    }
    // Joined with a blank line rather than with nothing: the splitter cut mid-text, so no join is
    // the original, and a visible break at least says where a chunk ends — which is the boundary a
    // retrieval that returned half an answer was cut on.
    final var text = chunks.stream().map(StoredChunk::text).collect(Collectors.joining("\n\n"));
    return Optional.of(new KnowledgeDocument(entryOf(chunks.getFirst().metadata()), text));
  }

  @Override
  public void delete(final KnowledgeScope scope, final String docId) {
    // Scoped rather than by id alone, so a docId belonging to someone else matches nothing instead
    // of deleting their document.
    store.delete(KnowledgeScopeFilter.document(scope, docId));
  }

  @Override
  public Optional<KnowledgeEntry> move(
      final KnowledgeScope scope, final String docId, final KnowledgeScope.Target target) {
    final var chunks = documentChunks(scope, docId);
    if (chunks.isEmpty()) {
      return Optional.empty();
    }
    final var head = chunks.getFirst().metadata();
    final var title = string(head, KnowledgeMetadata.TITLE);
    final var source = string(head, KnowledgeMetadata.SOURCE);
    final var createdAt = string(head, KnowledgeMetadata.CREATED_AT);

    // The chunks came back through the read filter, so a document found as OWN is this requester's
    // own and one found as GROUP is this group's — matching the target enum is therefore the whole
    // of "it is already there", and there is nothing to rewrite.
    if (targetOf(head) == target) {
      return Optional.of(entryOf(head));
    }

    final var owning = scope.owning(target);
    // Delete first, as index() does, and for the same reason: the rewritten chunks are readable by
    // the same requester, so a delete afterwards would take the move's own output with it. What
    // this costs is that a failure between the two loses the document rather than duplicating it,
    // which is the direction to fail in — a document silently existing twice in two scopes
    // contradicts itself in every later answer, and nobody finds out.
    store.delete(KnowledgeScopeFilter.document(scope, docId));

    final var moved = new ArrayList<Document>(chunks.size());
    for (var i = 0; i < chunks.size(); i++) {
      final var metadata = new LinkedHashMap<String, Object>();
      metadata.put(KnowledgeMetadata.OWNER, owning.owner());
      metadata.put(KnowledgeMetadata.GROUP, owning.group());
      metadata.put(KnowledgeMetadata.TENANT, owning.tenant());
      metadata.put(KnowledgeMetadata.DOC_ID, docId);
      metadata.put(KnowledgeMetadata.TITLE, title);
      metadata.put(KnowledgeMetadata.SOURCE, source);
      // The date the knowledge was written down, not the date it was moved: a move is not a new
      // document, and a listing ordered or read by this should not be reshuffled by one.
      metadata.put(KnowledgeMetadata.CREATED_AT, createdAt);
      // Numbers, not the strings they were read back as. The listing filter asks for chunk == 0 as
      // an integer, and a chunk that stored "0" would match nothing — the document would be in the
      // right scope and invisible to every listing.
      metadata.put(KnowledgeMetadata.CHUNK, i);
      metadata.put(KnowledgeMetadata.CHUNK_COUNT, chunks.size());
      moved.add(new Document(chunks.get(i).text(), metadata));
    }
    // Re-embedded rather than carried over. Milvus can hand back the vectors, but writing them
    // again means bypassing the store for the insert too, which is a second copy of its schema
    // here — a high price for saving an embedding call on an operation nobody runs in a loop.
    store.add(moved);
    log.debug("Moved {} ({} chunks) into the {} knowledge base", docId, moved.size(), target);

    return Optional.of(
        new KnowledgeEntry(docId, title, source, moved.size(), instant(createdAt), target));
  }

  @Override
  public List<Document> search(final KnowledgeScope scope, final String query, final int topK) {
    // The same scope filter as retrieval, and deliberately not the same similarity threshold: an
    // explicit search exists to show what is in there and what it scored, including when the
    // automatic threshold is set too high to let any of it through. Scoping is not relaxed with it.
    return VectorStoreDocumentRetriever.builder()
        .vectorStore(store)
        .filterExpression(KnowledgeScopeFilter.readableBy(scope))
        .similarityThreshold(0d)
        .topK(Math.max(1, topK))
        .build()
        .retrieve(org.springframework.ai.rag.Query.builder().text(query).build());
  }

  @Override
  public DocumentRetriever retrieverFor(final KnowledgeScope scope, final Filter.Expression extra) {
    final var rag = agentProperties.ai().rag();
    return VectorStoreDocumentRetriever.builder()
        .vectorStore(store)
        .filterExpression(KnowledgeScopeFilter.readableBy(scope, extra))
        .similarityThreshold(rag.similarityThreshold())
        .topK(rag.topK())
        .build();
  }

  /**
   * Tika for everything, rather than a plain reader with Tika as a special case: it handles text,
   * markdown and source files as readily as it handles a pdf, so one path is both simpler and less
   * to get wrong about which formats go where.
   */
  private String extractText(final String path) {
    final var file = Path.of(path);
    if (!Files.isReadable(file)) {
      throw new IllegalArgumentException("Cannot read the file: " + path);
    }
    try {
      return new TikaDocumentReader(new FileSystemResource(file))
          .get().stream().map(Document::getText).reduce((a, b) -> a + "\n" + b).orElse("");
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "Could not extract text from " + path + ": " + e.getMessage(), e);
    }
  }

  /**
   * The document's chunks in their own order.
   *
   * <p>Sorted here rather than by each caller: a query returns rows in whatever order the segments
   * hand them over, and a chunk's ordinal is the only record of where it belongs — so a document
   * rebuilt from an unsorted answer is a document with its paragraphs shuffled.
   */
  private List<StoredChunk> documentChunks(final KnowledgeScope scope, final String docId) {
    final var chunks = chunksOf(scope, docId);
    chunks.sort(
        Comparator.comparingInt(chunk -> integer(chunk.metadata(), KnowledgeMetadata.CHUNK)));
    return chunks;
  }

  /**
   * Every chunk of one document that {@code scope} may read, text and all.
   *
   * <p>Another raw query for the same reason {@link #list} is one: what is wanted is the rows of a
   * document, and a vector store's only way of returning content is a similarity search over a
   * query nobody has here.
   */
  private List<StoredChunk> chunksOf(final KnowledgeScope scope, final String docId) {
    final var response =
        client.query(
            QueryParam.newBuilder()
                .withCollectionName(properties.collectionName())
                .withExpr(toMilvus.convertExpression(KnowledgeScopeFilter.document(scope, docId)))
                .withOutFields(List.of(METADATA_FIELD, CONTENT_FIELD))
                .withLimit((long) MAX_CHUNKS_PER_DOCUMENT)
                // As in list(), and for the same reason: a document is commonly moved in the same
                // turn it was stored, and the default consistency level may not see it yet.
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .build());
    if (response.getData() == null) {
      throw new IllegalStateException(
          "Milvus refused the document query: " + response.getMessage());
    }

    final List<?> metadata;
    final List<?> contents;
    try {
      final var wrapper = new QueryResultsWrapper(response.getData());
      metadata = wrapper.getFieldWrapper(METADATA_FIELD).getFieldData();
      contents = wrapper.getFieldWrapper(CONTENT_FIELD).getFieldData();
    } catch (Exception e) {
      throw new IllegalStateException("Could not read document " + docId + " from Milvus", e);
    }
    if (metadata.size() >= MAX_CHUNKS_PER_DOCUMENT) {
      // Refused rather than moved in part. Taking the first page would leave the tail behind in the
      // scope being moved out of, and report success for it.
      throw new IllegalStateException(
          "Document "
              + docId
              + " has more than "
              + MAX_CHUNKS_PER_DOCUMENT
              + " chunks, which is more than a move can rewrite at once.");
    }

    final var chunks = new ArrayList<StoredChunk>(metadata.size());
    for (var i = 0; i < metadata.size(); i++) {
      chunks.add(new StoredChunk(String.valueOf(contents.get(i)), asMap(metadata.get(i))));
    }
    return chunks;
  }

  /** One stored chunk as the raw query returns it: its text, and its metadata flattened. */
  private record StoredChunk(String text, Map<String, String> metadata) {}

  private KnowledgeEntry toEntry(final Object row) {
    return entryOf(asMap(row));
  }

  private KnowledgeEntry entryOf(final Map<String, String> metadata) {
    return new KnowledgeEntry(
        string(metadata, KnowledgeMetadata.DOC_ID),
        string(metadata, KnowledgeMetadata.TITLE),
        string(metadata, KnowledgeMetadata.SOURCE),
        integer(metadata, KnowledgeMetadata.CHUNK_COUNT),
        instant(string(metadata, KnowledgeMetadata.CREATED_AT)),
        targetOf(metadata));
  }

  /**
   * Which knowledge base the document is in, read back from whichever scope field it was stamped
   * with.
   */
  private static KnowledgeScope.Target targetOf(final Map<String, String> metadata) {
    if (!string(metadata, KnowledgeMetadata.GROUP).isEmpty()) return KnowledgeScope.Target.GROUP;
    if (!string(metadata, KnowledgeMetadata.TENANT).isEmpty()) return KnowledgeScope.Target.TENANT;
    return KnowledgeScope.Target.OWN;
  }

  /** Flattens one row of the metadata column to strings. */
  private static Map<String, String> asMap(final Object row) {
    final var flat = new LinkedHashMap<String, String>();
    final var json = asJson(row);
    if (json == null) {
      return flat;
    }
    for (final var entry : json.entrySet()) {
      final var value = entry.getValue();
      flat.put(entry.getKey(), value == null || value.isJsonNull() ? "" : asScalar(value));
    }
    return flat;
  }

  /**
   * Milvus returns a JSON column as raw protobuf bytes rather than as anything parsed, and the SDK
   * hands those straight through, so this has to decode before it can read.
   *
   * <p>The other shapes are accepted because the SDK has changed which one it returns between
   * versions, and a silent disagreement here does not fail loudly: it yields a blank title and a
   * blank id on every row, which reads as corrupted data rather than as a parsing bug.
   */
  private static JsonObject asJson(final Object row) {
    if (row instanceof JsonObject json) {
      return json;
    }
    final String text;
    if (row instanceof ByteString bytes) {
      text = bytes.toStringUtf8();
    } else if (row instanceof byte[] bytes) {
      text = new String(bytes, StandardCharsets.UTF_8);
    } else if (row instanceof CharSequence sequence) {
      text = sequence.toString();
    } else {
      return null;
    }
    try {
      final var parsed = JsonParser.parseString(text);
      return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    } catch (RuntimeException e) {
      log.warn("Ignoring an unparseable knowledge metadata row", e);
      return null;
    }
  }

  /** Numbers arrive as numbers, so asking every value for its string is not enough on its own. */
  private static String asScalar(final JsonElement value) {
    return value.isJsonPrimitive() ? value.getAsString() : value.toString();
  }

  private static String string(final Map<String, String> metadata, final String key) {
    final var value = metadata.get(key);
    return value == null ? "" : value;
  }

  private static int integer(final Map<String, String> metadata, final String key) {
    try {
      return Integer.parseInt(string(metadata, key));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static Instant instant(final String value) {
    try {
      return value.isEmpty() ? null : Instant.parse(value);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
