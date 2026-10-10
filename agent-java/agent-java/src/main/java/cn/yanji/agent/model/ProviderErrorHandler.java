package cn.yanji.agent.model;

import cn.yanji.agent.contract.AgentFailure;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

/** Avoid Spring AI default exceptions containing the provider response body. */
public final class ProviderErrorHandler implements ResponseErrorHandler {
  @Override
  public boolean hasError(ClientHttpResponse response) throws IOException {
    return response.getStatusCode().isError();
  }

  @Override
  public void handleError(URI url, HttpMethod method, ClientHttpResponse response)
      throws IOException {
    int status = response.getStatusCode().value();
    String code =
        switch (status) {
          case 401 -> "MODEL_AUTH_FAILED";
          case 402, 403 -> "MODEL_ACCESS_DENIED";
          case 429 -> "MODEL_RATE_LIMITED";
          default -> "MODEL_REQUEST_FAILED";
        };
    throw new AgentFailure(
        code, "模型请求失败（HTTP " + status + "），请检查地域、权限或额度", status == 429 || status >= 500);
  }
}
