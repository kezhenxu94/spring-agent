# spring-agent-rag-milvus

> **Audience:** a developer implementing or extending a knowledge base. An operator turns it on with
> `app.ai.rag.enabled` (`RAG_ENABLED`) and points `app.ai.rag.milvus.*` at a Milvus; the property
> reference is that block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

The knowledge base: retrieval over what a user, group or tenant has asked the agent to remember. It is
the only implementation of core's `KnowledgeBase` SPI, and it is a separate module because a knowledge
base needs more of a store than any portable interface offers.

## It is not the tool-search vector store

Confusing the two is the easy mistake here.

- **`spring.ai.vectorstore.type`** (`simple` in-heap, or `milvus`) backs the **tool-search index**
  only — embeddings of tool descriptions, so a run is offered the few tools it needs.
- **This module** is retrieval over **user data**, in its own Milvus collection with its own
  connection under `app.ai.rag.milvus.*`. It deliberately does not read `spring.ai.vectorstore.type`,
  so a deployment can run the tool index in the heap and the knowledge base in Milvus. That is exactly
  what `RAG_ENABLED=true COMPOSE_PROFILES=rag docker compose up` gives you.

## Two things that look like tidiness and are not

**The `MilvusVectorStore` is a private field, not a bean.** Spring AI's Milvus auto-configuration
declares its own store `@ConditionalOnMissingBean`, so publishing a second one would make *that* back
off and silently take the tool-search index's store with it.

**It drops to the raw Milvus client for `list`.** No portable `VectorStore` interface can enumerate,
and enumeration is what a knowledge base needs to be listable and readable in a page. That is the
whole reason a knowledge base is a backend module rather than something core implements over any
store.

## Scoping

One definition, `core/knowledge/KnowledgeScopeFilter`, used both for retrieval and — via
`MilvusFilterExpressionConverter` — for the raw listing query.

Chunks carry `owner`, `group` and `tenant`, always all three, blank where they do not apply. **A filter
clause is only ever emitted for a non-blank identity**: a blank one would match every document that
stores a blank there, which is every other user's. `KnowledgeScopeFilterTest` covers that case by
name; read it before changing the filter.

## Registering the tools

Core registers the knowledge tools only when a `KnowledgeBase` bean exists, ordered with
`@AutoConfiguration(afterName = ...)` naming `MilvusKnowledgeAutoConfiguration` **as a string** —
because core may not depend on this module. Rename that class and the tools silently stop being
registered.

## Turning it on

`RAG_ENABLED=true` and a reachable Milvus. The two go together: turning it on with no Milvus reachable
stops startup rather than quietly running without a knowledge base, which is the same reasoning as
`app.ai.tools.shell.type` defaulting to `none`.
