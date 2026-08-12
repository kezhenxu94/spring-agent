package me.kezhenxu94.springagent.integration.feishu.aot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lark.oapi.core.request.SelfBuiltAppAccessTokenReq;
import com.lark.oapi.core.response.TenantAccessTokenResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * The gap these cover is invisible to every other test: on the JVM reflection works whether or not
 * a type was registered, so a missing hint only shows up as a native binary sending an empty
 * request body. Asserting on the hints themselves is the only place it can be caught before the
 * image is built.
 */
class LarkSdkRuntimeHintsTest {

  private RuntimeHints hints;

  @BeforeEach
  void registerHints() {
    hints = new RuntimeHints();
    new LarkSdkRuntimeHints().registerHints(hints, getClass().getClassLoader());
  }

  @Test
  void registersTheTokenRequestBody() {
    // Gson reads app_id and app_secret off this by field; unregistered it serialises to an empty
    // object and the token endpoint answers "invalid param".
    assertTrue(
        RuntimeHintsPredicates.reflection()
            .onType(SelfBuiltAppAccessTokenReq.class)
            .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS)
            .test(hints));
  }

  @Test
  void registersTheTokenResponse() {
    assertTrue(
        RuntimeHintsPredicates.reflection()
            .onType(TenantAccessTokenResp.class)
            .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS)
            .test(hints));
  }
}
