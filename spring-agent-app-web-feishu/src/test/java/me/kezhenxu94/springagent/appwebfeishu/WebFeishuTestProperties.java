package me.kezhenxu94.springagent.appwebfeishu;

/**
 * What has to resolve for this application's context to refresh in a test.
 *
 * <p>{@code application.yaml} declares these as placeholders with no default, deliberately — a
 * server that cannot reach a model should not start pretending it can — so a test has to supply
 * them. Nothing here is called; they only have to resolve.
 *
 * <p>Shared between the tests in this module rather than copied into each, because the list is the
 * kind that grows: a knob added to the shipped configuration with no default breaks every test in
 * the module at once, and the fix belongs in one place.
 */
final class WebFeishuTestProperties {

  private WebFeishuTestProperties() {}

  static final String ALL =
      """
      spring.ai.openai.base-url=http://localhost:1
      spring.ai.openai.api-key=test
      spring.ai.openai.chat.model=test-model
      spring.ai.openai.embedding.base-url=http://localhost:1
      spring.ai.openai.embedding.api-key=test
      spring.ai.openai.embedding.model=test-embedding
      spring.ai.openai.audio.transcription.base-url=http://localhost:1
      spring.ai.openai.audio.transcription.api-key=test
      spring.security.oauth2.client.registration.feishu.client-id=cli_test
      spring.security.oauth2.client.registration.feishu.client-secret=test
      # The same app id on both sides, which is what FeishuIdentityCheck is about: the shipped
      # configuration reads FEISHU_APP_ID for each, and a test that set them apart would be a test
      # of a configuration this application refuses to start on.
      app.feishu.appId=cli_test
      app.feishu.appSecret=test
      app.feishu.encrypt-key=test
      app.feishu.tenant-id=tenant-under-test
      app.feishu.tenant-domain=example.invalid
      app.feishu.bot-open-id=ou_bot
      app.web.auth.tenant-id=tenant-under-test
      app.ai.tools.shell.type=none
      spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-feishu-test.db
      """;
}
