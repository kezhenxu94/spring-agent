package me.kezhenxu94.springagent.integration.feishu.aot;

import com.google.gson.annotations.JsonAdapter;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableRecordResp;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableReq;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableResp;
import com.lark.oapi.service.bitable.v1.model.BatchDeleteAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchDeleteAppTableRecordResp;
import com.lark.oapi.service.bitable.v1.model.BatchDeleteAppTableReq;
import com.lark.oapi.service.bitable.v1.model.BatchDeleteAppTableResp;
import com.lark.oapi.service.bitable.v1.model.BatchGetAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchGetAppTableRecordResp;
import com.lark.oapi.service.bitable.v1.model.BatchUpdateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchUpdateAppTableRecordResp;
import com.lark.oapi.service.bitable.v1.model.CreateAppReq;
import com.lark.oapi.service.bitable.v1.model.CreateAppResp;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableFieldReq;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableFieldResp;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableRecordResp;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableReq;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableResp;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableViewReq;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableViewResp;
import com.lark.oapi.service.bitable.v1.model.DeleteAppTableViewReq;
import com.lark.oapi.service.bitable.v1.model.DeleteAppTableViewResp;
import com.lark.oapi.service.bitable.v1.model.GetAppReq;
import com.lark.oapi.service.bitable.v1.model.GetAppResp;
import com.lark.oapi.service.bitable.v1.model.GetAppTableViewReq;
import com.lark.oapi.service.bitable.v1.model.GetAppTableViewResp;
import com.lark.oapi.service.bitable.v1.model.ListAppTableFieldReq;
import com.lark.oapi.service.bitable.v1.model.ListAppTableFieldResp;
import com.lark.oapi.service.bitable.v1.model.ListAppTableReq;
import com.lark.oapi.service.bitable.v1.model.ListAppTableResp;
import com.lark.oapi.service.bitable.v1.model.ListAppTableViewReq;
import com.lark.oapi.service.bitable.v1.model.ListAppTableViewResp;
import com.lark.oapi.service.bitable.v1.model.PatchAppTableReq;
import com.lark.oapi.service.bitable.v1.model.PatchAppTableResp;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordResp;
import com.lark.oapi.service.bitable.v1.model.UpdateAppReq;
import com.lark.oapi.service.bitable.v1.model.UpdateAppResp;
import com.lark.oapi.service.bitable.v1.model.UpdateAppTableFieldReq;
import com.lark.oapi.service.bitable.v1.model.UpdateAppTableFieldResp;
import com.lark.oapi.service.bitable.v1.model.UpdateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.UpdateAppTableRecordResp;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReqBody;
import com.lark.oapi.service.docx.v1.model.BatchDeleteDocumentBlockChildrenReq;
import com.lark.oapi.service.docx.v1.model.BatchDeleteDocumentBlockChildrenReqBody;
import com.lark.oapi.service.docx.v1.model.BatchUpdateDocumentBlockReq;
import com.lark.oapi.service.docx.v1.model.BatchUpdateDocumentBlockReqBody;
import com.lark.oapi.service.docx.v1.model.Block;
import com.lark.oapi.service.docx.v1.model.ConvertDocumentReq;
import com.lark.oapi.service.docx.v1.model.ConvertDocumentReqBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockChildrenReq;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockChildrenReqBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockDescendantReq;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockDescendantReqBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentReq;
import com.lark.oapi.service.docx.v1.model.CreateDocumentReqBody;
import com.lark.oapi.service.docx.v1.model.Document;
import com.lark.oapi.service.docx.v1.model.GetDocumentBlockChildrenReq;
import com.lark.oapi.service.docx.v1.model.GetDocumentBlockReq;
import com.lark.oapi.service.docx.v1.model.GetDocumentReq;
import com.lark.oapi.service.docx.v1.model.ListDocumentBlockReq;
import com.lark.oapi.service.docx.v1.model.PatchDocumentBlockReq;
import com.lark.oapi.service.docx.v1.model.RawContentDocumentReq;
import com.lark.oapi.service.docx.v1.model.UpdateBlockRequest;
import com.lark.oapi.service.drive.v1.model.BaseMember;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberReqBody;
import com.lark.oapi.service.drive.v1.model.CreateExportTaskReq;
import com.lark.oapi.service.drive.v1.model.CreateExportTaskResp;
import com.lark.oapi.service.drive.v1.model.CreateFolderFileReq;
import com.lark.oapi.service.drive.v1.model.CreateFolderFileResp;
import com.lark.oapi.service.drive.v1.model.CreateImportTaskReq;
import com.lark.oapi.service.drive.v1.model.CreateImportTaskResp;
import com.lark.oapi.service.drive.v1.model.DownloadExportTaskReq;
import com.lark.oapi.service.drive.v1.model.DownloadExportTaskResp;
import com.lark.oapi.service.drive.v1.model.DownloadFileReq;
import com.lark.oapi.service.drive.v1.model.FileUploadInfo;
import com.lark.oapi.service.drive.v1.model.GetExportTaskReq;
import com.lark.oapi.service.drive.v1.model.GetExportTaskResp;
import com.lark.oapi.service.drive.v1.model.GetImportTaskReq;
import com.lark.oapi.service.drive.v1.model.GetImportTaskResp;
import com.lark.oapi.service.drive.v1.model.ListFileReq;
import com.lark.oapi.service.drive.v1.model.ListFileResp;
import com.lark.oapi.service.drive.v1.model.ListPermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.ListPermissionMemberResp;
import com.lark.oapi.service.drive.v1.model.TransferOwnerPermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.TransferOwnerPermissionMemberResp;
import com.lark.oapi.service.drive.v1.model.UploadAllFileReq;
import com.lark.oapi.service.drive.v1.model.UploadAllFileReqBody;
import com.lark.oapi.service.drive.v1.model.UploadAllFileResp;
import com.lark.oapi.service.drive.v1.model.UploadAllMediaReq;
import com.lark.oapi.service.drive.v1.model.UploadAllMediaReqBody;
import com.lark.oapi.service.drive.v1.model.UploadFinishFileReq;
import com.lark.oapi.service.drive.v1.model.UploadFinishFileReqBody;
import com.lark.oapi.service.drive.v1.model.UploadFinishFileResp;
import com.lark.oapi.service.drive.v1.model.UploadPartFileReq;
import com.lark.oapi.service.drive.v1.model.UploadPartFileReqBody;
import com.lark.oapi.service.drive.v1.model.UploadPartFileResp;
import com.lark.oapi.service.drive.v1.model.UploadPrepareFileReq;
import com.lark.oapi.service.drive.v1.model.UploadPrepareFileResp;
import com.lark.oapi.service.im.v1.model.CreateFileReq;
import com.lark.oapi.service.im.v1.model.CreateFileReqBody;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.DeleteMessageReq;
import com.lark.oapi.service.im.v1.model.DeleteMessageResp;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.GetChatMembersReq;
import com.lark.oapi.service.im.v1.model.GetChatMembersResp;
import com.lark.oapi.service.im.v1.model.GetChatReq;
import com.lark.oapi.service.im.v1.model.GetChatResp;
import com.lark.oapi.service.im.v1.model.GetMessageReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResp;
import com.lark.oapi.service.im.v1.model.IsInChatChatMembersReq;
import com.lark.oapi.service.im.v1.model.IsInChatChatMembersResp;
import com.lark.oapi.service.im.v1.model.ListChatReq;
import com.lark.oapi.service.im.v1.model.ListChatResp;
import com.lark.oapi.service.im.v1.model.ListMessageResp;
import com.lark.oapi.service.im.v1.model.P1P2PChatCreatedV1;
import com.lark.oapi.service.im.v1.model.P2ChatAccessEventBotP2pChatEnteredV1;
import com.lark.oapi.service.im.v1.model.P2MessageReadV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.sheets.v3.model.CreateSpreadsheetReq;
import com.lark.oapi.service.sheets.v3.model.QuerySpreadsheetSheetReq;
import com.lark.oapi.service.sheets.v3.model.Spreadsheet;
import com.lark.oapi.service.wiki.v2.model.GetNodeSpaceReq;
import com.lark.oapi.service.wiki.v2.model.ListSpaceMemberReq;
import com.lark.oapi.service.wiki.v2.model.ListSpaceMemberResp;
import com.lark.oapi.service.wiki.v2.model.ListSpaceNodeReq;
import com.lark.oapi.service.wiki.v2.model.ListSpaceNodeResp;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection hints for the Feishu SDK, which binds its whole model layer with Gson and ships no
 * reachability metadata of its own.
 *
 * <p>The SDK jar holds some 19,000 classes, 15,000 of them models for services this application
 * never calls, so registering by package would bloat the image and its build for no benefit.
 * Instead this seeds from the model types this module names directly and walks their field types
 * transitively, bounded to the SDK's own package, which is exactly the set Gson can reach. Adding a
 * new endpoint extends the closure automatically as soon as its request or response type is
 * referenced here — no generated metadata to regenerate.
 *
 * <p>Imported from {@code FeishuAutoConfiguration} rather than registered globally, so an
 * application that switches the Feishu integration off at build time does not carry the closure.
 */
public class LarkSdkRuntimeHints implements RuntimeHintsRegistrar {

  private static final String SDK_PACKAGE = "com.lark.oapi.";

  /** Mechanically the {@code com.lark.oapi.**.model} types imported across this module. */
  private static final List<Class<?>> ROOTS =
      List.of(
          // Bitable, request and response both: unlike the other services listed here, whose
          // response types were never named, Gson deserialises the whole *Resp into being, so a
          // response with no field access parses to an object whose every member is null.
          BatchCreateAppTableRecordReq.class,
          BatchCreateAppTableRecordResp.class,
          BatchCreateAppTableReq.class,
          BatchCreateAppTableResp.class,
          BatchDeleteAppTableRecordReq.class,
          BatchDeleteAppTableRecordResp.class,
          BatchDeleteAppTableReq.class,
          BatchDeleteAppTableResp.class,
          BatchGetAppTableRecordReq.class,
          BatchGetAppTableRecordResp.class,
          BatchUpdateAppTableRecordReq.class,
          BatchUpdateAppTableRecordResp.class,
          CreateAppReq.class,
          CreateAppResp.class,
          CreateAppTableFieldReq.class,
          CreateAppTableFieldResp.class,
          UpdateAppTableFieldReq.class,
          UpdateAppTableFieldResp.class,
          CreateAppTableRecordReq.class,
          CreateAppTableRecordResp.class,
          CreateAppTableReq.class,
          CreateAppTableResp.class,
          CreateAppTableViewReq.class,
          CreateAppTableViewResp.class,
          DeleteAppTableViewReq.class,
          DeleteAppTableViewResp.class,
          GetAppReq.class,
          GetAppResp.class,
          GetAppTableViewReq.class,
          GetAppTableViewResp.class,
          ListAppTableFieldReq.class,
          ListAppTableFieldResp.class,
          ListAppTableReq.class,
          ListAppTableResp.class,
          ListAppTableViewReq.class,
          ListAppTableViewResp.class,
          PatchAppTableReq.class,
          PatchAppTableResp.class,
          SearchAppTableRecordReq.class,
          SearchAppTableRecordResp.class,
          UpdateAppReq.class,
          UpdateAppResp.class,
          UpdateAppTableRecordReq.class,
          UpdateAppTableRecordResp.class,
          P2CardActionTrigger.class,
          P2CardActionTriggerResponse.class,
          ContentCardElementReq.class,
          ContentCardElementReqBody.class,
          CreateCardReq.class,
          CreateCardReqBody.class,
          DeleteCardElementReq.class,
          DeleteCardElementReqBody.class,
          SettingsCardReq.class,
          SettingsCardReqBody.class,
          BatchDeleteDocumentBlockChildrenReq.class,
          BatchDeleteDocumentBlockChildrenReqBody.class,
          BatchUpdateDocumentBlockReq.class,
          BatchUpdateDocumentBlockReqBody.class,
          Block.class,
          ConvertDocumentReq.class,
          ConvertDocumentReqBody.class,
          CreateDocumentBlockChildrenReq.class,
          CreateDocumentBlockChildrenReqBody.class,
          CreateDocumentBlockDescendantReq.class,
          CreateDocumentBlockDescendantReqBody.class,
          CreateDocumentReq.class,
          CreateDocumentReqBody.class,
          Document.class,
          GetDocumentBlockChildrenReq.class,
          GetDocumentBlockReq.class,
          GetDocumentReq.class,
          ListDocumentBlockReq.class,
          PatchDocumentBlockReq.class,
          RawContentDocumentReq.class,
          UpdateBlockRequest.class,
          BaseMember.class,
          BatchCreatePermissionMemberReq.class,
          BatchCreatePermissionMemberReqBody.class,
          CreateFolderFileReq.class,
          CreateFolderFileResp.class,
          ListPermissionMemberReq.class,
          ListPermissionMemberResp.class,
          TransferOwnerPermissionMemberReq.class,
          TransferOwnerPermissionMemberResp.class,
          ListSpaceMemberReq.class,
          ListSpaceMemberResp.class,
          DownloadFileReq.class,
          // Import and export, request and response both: the ticket a task is started with and
          // the status it is polled for are read back out of the responses by Gson, so a response
          // left unregistered polls a task whose every field is null and never finishes.
          CreateImportTaskReq.class,
          CreateImportTaskResp.class,
          GetImportTaskReq.class,
          GetImportTaskResp.class,
          CreateExportTaskReq.class,
          CreateExportTaskResp.class,
          GetExportTaskReq.class,
          GetExportTaskResp.class,
          DownloadExportTaskReq.class,
          DownloadExportTaskResp.class,
          ListFileReq.class,
          ListFileResp.class,
          UploadAllMediaReq.class,
          UploadAllMediaReqBody.class,
          // Uploading a file into a folder, request and response both: the file token an upload
          // answers with, and the chunk size and count the pre-upload dictates, are read back out
          // of the responses by Gson, so a response left unregistered uploads nothing and reports
          // no token.
          UploadAllFileReq.class,
          UploadAllFileReqBody.class,
          UploadAllFileResp.class,
          UploadPrepareFileReq.class,
          UploadPrepareFileResp.class,
          FileUploadInfo.class,
          UploadPartFileReq.class,
          UploadPartFileReqBody.class,
          UploadPartFileResp.class,
          UploadFinishFileReq.class,
          UploadFinishFileReqBody.class,
          UploadFinishFileResp.class,
          CreateFileReq.class,
          CreateFileReqBody.class,
          CreateImageReq.class,
          CreateImageReqBody.class,
          CreateMessageReq.class,
          CreateMessageReqBody.class,
          DeleteMessageReq.class,
          DeleteMessageResp.class,
          EventMessage.class,
          GetChatMembersReq.class,
          GetChatMembersResp.class,
          GetChatReq.class,
          GetChatResp.class,
          GetMessageReq.class,
          GetMessageResourceReq.class,
          GetMessageResp.class,
          IsInChatChatMembersReq.class,
          IsInChatChatMembersResp.class,
          ListChatReq.class,
          ListChatResp.class,
          ListMessageResp.class,
          P2MessageReadV1.class,
          P2MessageReceiveV1.class,
          P1P2PChatCreatedV1.class,
          P2ChatAccessEventBotP2pChatEnteredV1.class,
          ReplyMessageReq.class,
          ReplyMessageReqBody.class,
          CreateSpreadsheetReq.class,
          QuerySpreadsheetSheetReq.class,
          Spreadsheet.class,
          GetNodeSpaceReq.class,
          ListSpaceNodeReq.class,
          ListSpaceNodeResp.class);

  /**
   * Our own {@code @Query} holders, registered by name because they are package-private to {@code
   * ..feishu.tools}. The SDK's {@code ReqTranslator} reads their query parameters with {@code
   * getClass().getDeclaredFields()}, so without field access it finds none and silently sends a
   * request with no query string.
   */
  private static final List<String> QUERY_HOLDERS =
      List.of(
          "me.kezhenxu94.springagent.integration.feishu.tools.ListMessageQuery",
          "me.kezhenxu94.springagent.integration.feishu.tools.GetMessageQuery");

  /**
   * Fields because Gson reads and writes them directly and {@code ReqTranslator} enumerates them;
   * constructors and methods for the SDK's builders; {@code UNSAFE_ALLOCATED} because Gson falls
   * back to unsafe allocation for a model with no usable no-arg constructor.
   */
  private static final MemberCategory[] GSON_MODEL = {
    MemberCategory.ACCESS_DECLARED_FIELDS,
    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
    MemberCategory.INVOKE_DECLARED_METHODS,
    MemberCategory.UNSAFE_ALLOCATED,
  };

  /**
   * SDK-internal models that Gson binds without this module ever naming them, so they cannot be
   * reached from {@link #ROOTS}. Seeded by name and then walked like any other root.
   *
   * <p>{@code com.lark.oapi.ws.model} is the long-connection handshake: {@code
   * com.lark.oapi.ws.Client} deserialises {@code EndpointResp} to learn where to connect, and
   * failing that it retries forever, so the context never finishes refreshing and the application
   * appears to hang rather than fail.
   *
   * <p>{@code com.lark.oapi.event.model} is the event envelope {@code
   * EventDispatcher.doWithoutValidation} unwraps before dispatching to a handler. Failing that, the
   * websocket connects and then drops every inbound event with "handle message failed".
   *
   * <p>{@code com.lark.oapi.core.response} is what every outbound call goes through: {@code
   * TokenManager} deserialises a {@code TenantAccessTokenResp} before the first request is even
   * signed, and each reply comes back as a {@code BaseResponse} with an {@code Error} body. The
   * whole package is seeded rather than the types a stack trace happened to name, because any call
   * can be the one that reaches an unregistered one.
   *
   * <p>{@code com.lark.oapi.core.request} is the other half of that exchange: the credential bodies
   * the token endpoints receive, picked at runtime by {@code Config.isMarketPlaceApp()} and by the
   * app-ticket resend, so nothing here names them. {@code ReqTranslator} decides from {@code
   * getDeclaredFields()} that they carry no {@code @Body}/{@code @Path}/{@code @Query} and passes
   * the object itself as the body, which Gson then reads by field as well — an unregistered one has
   * no fields either way, so it serialises to an empty JSON object and comes back as "invalid
   * param" rather than failing anywhere near the missing registration. Only these five, not the
   * package: {@code FormData} and {@code FormDataFile} are read through their getters by {@code
   * OkHttpTransport}, {@code RequestOptions} and {@code EventReq} never reach Gson, and {@code
   * RawRequest} holds a {@code Config}, which would pull the shaded HTTP client into the walk.
   */
  private static final List<String> INTERNAL_MODEL_SEEDS =
      List.of(
          "com.lark.oapi.ws.model.EndpointResp",
          "com.lark.oapi.ws.model.Endpoint",
          "com.lark.oapi.ws.model.ClientConfig",
          "com.lark.oapi.ws.model.Response",
          "com.lark.oapi.event.model.Event",
          "com.lark.oapi.event.model.BaseEvent",
          "com.lark.oapi.event.model.BaseEventV2",
          "com.lark.oapi.event.model.BaseEventData",
          "com.lark.oapi.event.model.Header",
          "com.lark.oapi.event.model.Fuzzy",
          "com.lark.oapi.event.model.AppTicketEvent",
          "com.lark.oapi.event.model.AppTicketEvent$AppTicketEventData",
          "com.lark.oapi.core.response.BaseResponse",
          "com.lark.oapi.core.response.RawResponse",
          "com.lark.oapi.core.response.Body",
          "com.lark.oapi.core.response.EmptyData",
          "com.lark.oapi.core.response.EventResp",
          "com.lark.oapi.core.response.TenantAccessTokenResp",
          "com.lark.oapi.core.response.AppAccessTokenResp",
          "com.lark.oapi.core.response.error.Error",
          "com.lark.oapi.core.response.error.ErrorDetail",
          "com.lark.oapi.core.response.error.ErrorHelp",
          "com.lark.oapi.core.response.error.ErrorFieldViolation",
          "com.lark.oapi.core.response.error.ErrorPermissionViolation",
          "com.lark.oapi.core.request.SelfBuiltAppAccessTokenReq",
          "com.lark.oapi.core.request.SelfBuiltTenantAccessTokenReq",
          "com.lark.oapi.core.request.MarketplaceAppAccessTokenReq",
          "com.lark.oapi.core.request.MarketplaceTenantAccessTokenReq",
          "com.lark.oapi.core.request.ResendAppTicketReq");

  /**
   * The websocket long-connection transport. Only two protobuf messages are generated; the rest of
   * the shaded protobuf jar is runtime plumbing that needs nothing.
   */
  private static final List<String> WEBSOCKET_TRANSPORT =
      List.of(
          "com.lark.oapi.ws.pb.Pbbp2",
          "com.lark.oapi.ws.pb.Pbbp2$Frame",
          "com.lark.oapi.ws.pb.Pbbp2$Frame$Builder",
          "com.lark.oapi.ws.pb.Pbbp2$Header",
          "com.lark.oapi.ws.pb.Pbbp2$Header$Builder",
          "com.lark.oapi.ws.pb.GoGoProtos");

  /**
   * The SDK shades OkHttp, so the upstream OkHttp metadata in the shared reachability repository
   * cannot match these names. OkHttp picks its TLS platform reflectively at startup.
   */
  private static final List<String> SHADED_OKHTTP_PLATFORMS =
      List.of(
          "com.lark.oapi.okhttp.internal.platform.Jdk9Platform",
          "com.lark.oapi.okhttp.internal.platform.Jdk8WithJettyBootPlatform",
          "com.lark.oapi.okhttp.internal.platform.Jdk8WithJettyBootPlatform$AlpnProvider",
          "com.lark.oapi.okhttp.internal.platform.ConscryptPlatform",
          "com.lark.oapi.okhttp.internal.platform.Platform");

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    final Set<Class<?>> visited = new HashSet<>();
    final var pending = new ArrayDeque<>(ROOTS);
    // Seeded into the same walk as ROOTS so their own field types are covered as well; they are
    // named as strings only because this module has no compile dependency on the SDK's internals.
    for (final String name : INTERNAL_MODEL_SEEDS) {
      try {
        pending.add(Class.forName(name, false, classLoader));
      } catch (ClassNotFoundException e) {
        // A newer SDK renamed or dropped it; the handshake failure that follows is self-describing.
      }
    }
    while (!pending.isEmpty()) {
      final var type = pending.poll();
      if (!visited.add(type)) {
        continue;
      }
      hints.reflection().registerType(type, GSON_MODEL);
      registerJsonAdapter(hints, type.getAnnotation(JsonAdapter.class));

      for (final Field field : type.getDeclaredFields()) {
        // A @JsonAdapter names its adapter as a class literal, which Gson then instantiates
        // reflectively — nothing calls its constructor, so the analysis cannot see it. Missing this
        // fails the whole enclosing object, not just the annotated field.
        registerJsonAdapter(hints, field.getAnnotation(JsonAdapter.class));

        // Every instance field, not only the @SerializedName-annotated ones: Gson falls back to the
        // plain field name when the annotation is absent, and the SDK's internal models carry no
        // annotations at all. Filtering on the annotation silently truncated the closure there.
        if (!Modifier.isStatic(field.getModifiers())) {
          enqueue(field.getGenericType(), pending);
        }
      }
      // Request bodies and responses share abstract bases that carry wire fields of their own.
      final var superclass = type.getSuperclass();
      if (superclass != null && isSdkModel(superclass)) {
        pending.add(superclass);
      }
    }

    for (final String name : QUERY_HOLDERS) {
      hints.reflection().registerTypeIfPresent(classLoader, name, GSON_MODEL);
    }
    for (final String name : WEBSOCKET_TRANSPORT) {
      hints
          .reflection()
          .registerTypeIfPresent(
              classLoader,
              name,
              MemberCategory.ACCESS_DECLARED_FIELDS,
              MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
              MemberCategory.INVOKE_DECLARED_METHODS);
    }
    // What FeishuLongConnection reaches into: the websocket client's own conn and executor fields
    // and its disconnect() method, all protected with no accessor and no public equivalent. Read
    // that class for why. Missing this is a native image that starts, connects, and then fails on
    // the first supervision check — the JVM build says nothing about it.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            "com.lark.oapi.ws.Client",
            MemberCategory.ACCESS_DECLARED_FIELDS,
            MemberCategory.INVOKE_DECLARED_METHODS);
    for (final String name : SHADED_OKHTTP_PLATFORMS) {
      hints
          .reflection()
          .registerTypeIfPresent(
              classLoader,
              name,
              MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
              MemberCategory.INVOKE_DECLARED_METHODS);
    }
  }

  /**
   * Registers the adapter a {@code @JsonAdapter} points at. Not restricted to the SDK's package: an
   * adapter may live anywhere, and registering one is cheap.
   */
  private static void registerJsonAdapter(final RuntimeHints hints, final JsonAdapter annotation) {
    if (annotation == null) {
      return;
    }
    hints
        .reflection()
        .registerType(
            annotation.value(),
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.UNSAFE_ALLOCATED);
  }

  /** Follows arrays and generic type arguments, since Gson binds through both. */
  private static void enqueue(final Type type, final ArrayDeque<Class<?>> pending) {
    if (type instanceof Class<?> clazz) {
      final var element = clazz.isArray() ? clazz.getComponentType() : clazz;
      if (isSdkModel(element)) {
        pending.add(element);
      }
    } else if (type instanceof ParameterizedType parameterized) {
      enqueue(parameterized.getRawType(), pending);
      for (final Type argument : parameterized.getActualTypeArguments()) {
        enqueue(argument, pending);
      }
    }
  }

  private static boolean isSdkModel(final Class<?> type) {
    return type.getName().startsWith(SDK_PACKAGE) && !type.isInterface();
  }
}
