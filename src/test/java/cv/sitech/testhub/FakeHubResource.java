package cv.sitech.testhub;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cv.sitech.adapter.fspiop.Fspiop;
import cv.sitech.application.service.IlpService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * In-process stand-in for a Mojaloop hub, used by tests: an ALS oracle, a quoting service and an
 * ml-api-adapter that route FSPIOP messages to {@code /fspiop/{destination}} of this same app, plus
 * the central-ledger admin and central-settlement reads the simulator needs. Like the real hub it
 * acknowledges first and forwards asynchronously.
 */
@Path("/test-hub")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
public class FakeHubResource {

  public static final String SIM = "http://localhost:8081/fspiop/";

  /** ALS oracle: "TYPE/id" -> fspId */
  public static final Map<String, String> ORACLE = new ConcurrentHashMap<>();
  /** hub positions per fspId */
  public static final Map<String, BigDecimal> POSITIONS = new ConcurrentHashMap<>();
  /** admin calls received, as "METHOD path body" */
  public static final List<String> ADMIN_CALLS = new CopyOnWriteArrayList<>();
  /** FSPIOP requests received from the simulator, as "METHOD path" */
  public static final List<String> FSPIOP_CALLS = new CopyOnWriteArrayList<>();
  /** central-settlement GET /settlements response */
  public static volatile String settlementsJson = "[]";

  private static final Map<String, JsonNode> TRANSFERS = new ConcurrentHashMap<>();
  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final ObjectMapper JSON = new ObjectMapper();

  // --- ALS ---

  @POST
  @Path("/als/participants/{type}/{id}")
  public Response registerParticipant(@PathParam("type") String type, @PathParam("id") String id,
      @HeaderParam(Fspiop.SOURCE) String source, String body) throws Exception {
    String fspId = JSON.readTree(body).path("fspId").asText();
    ORACLE.put(type + "/" + id, fspId);
    forward("PUT", fspId + "/participants/" + type + "/" + id, "participants", "hub", fspId,
        "{\"fspId\":\"" + fspId + "\"}");
    return Response.accepted().build();
  }

  @DELETE
  @Path("/als/participants/{type}/{id}")
  public Response deleteParticipant(@PathParam("type") String type, @PathParam("id") String id) {
    ORACLE.remove(type + "/" + id);
    return Response.accepted().build();
  }

  @GET
  @Path("/als/parties/{type}/{id}")
  public Response getParties(@PathParam("type") String type, @PathParam("id") String id,
      @HeaderParam(Fspiop.SOURCE) String source) {
    FSPIOP_CALLS.add("GET /parties/" + type + "/" + id);
    String payee = ORACLE.get(type + "/" + id);
    if (payee == null) {
      forward("PUT", source + "/parties/" + type + "/" + id + "/error", "parties", "hub", source,
          "{\"errorInformation\":{\"errorCode\":\"3204\",\"errorDescription\":\"Party not found\"}}");
    } else {
      forward("GET", payee + "/parties/" + type + "/" + id, "parties", source, payee, null);
    }
    return Response.accepted().build();
  }

  @PUT
  @Path("/als/parties/{segments: .+}")
  public Response putParties(@PathParam("segments") String segments, @HeaderParam(Fspiop.SOURCE) String source,
      @HeaderParam(Fspiop.DESTINATION) String destination, String body) {
    FSPIOP_CALLS.add("PUT /parties/" + segments);
    forward("PUT", destination + "/parties/" + segments, "parties", source, destination, body);
    return Response.ok().build();
  }

  // --- quoting service ---

  @POST
  @Path("/quotes/quotes")
  public Response postQuotes(@HeaderParam(Fspiop.SOURCE) String source,
      @HeaderParam(Fspiop.DESTINATION) String destination, String body) {
    FSPIOP_CALLS.add("POST /quotes");
    forward("POST", destination + "/quotes", "quotes", source, destination, body);
    return Response.accepted().build();
  }

  @PUT
  @Path("/quotes/quotes/{segments: .+}")
  public Response putQuotes(@PathParam("segments") String segments, @HeaderParam(Fspiop.SOURCE) String source,
      @HeaderParam(Fspiop.DESTINATION) String destination, String body) {
    FSPIOP_CALLS.add("PUT /quotes/" + segments);
    forward("PUT", destination + "/quotes/" + segments, "quotes", source, destination, body);
    return Response.ok().build();
  }

  // --- ml-api-adapter / central-ledger transfers ---

  @POST
  @Path("/transfers/transfers")
  public Response postTransfers(@HeaderParam(Fspiop.SOURCE) String source,
      @HeaderParam(Fspiop.DESTINATION) String destination, String body) throws Exception {
    FSPIOP_CALLS.add("POST /transfers");
    JsonNode transfer = JSON.readTree(body);
    TRANSFERS.put(transfer.path("transferId").asText(), transfer);
    forward("POST", transfer.path("payeeFsp").asText() + "/transfers", "transfers", source, destination, body);
    return Response.accepted().build();
  }

  @PUT
  @Path("/transfers/transfers/{id}")
  public Response putTransfer(@PathParam("id") String transferId, String body) throws Exception {
    FSPIOP_CALLS.add("PUT /transfers/" + transferId);
    JsonNode fulfil = JSON.readTree(body);
    JsonNode transfer = TRANSFERS.get(transferId);
    String payer = transfer.path("payerFsp").asText();
    String payee = transfer.path("payeeFsp").asText();
    if (!IlpService.matches(fulfil.path("fulfilment").asText(), transfer.path("condition").asText())) {
      String error = "{\"errorInformation\":{\"errorCode\":\"3106\",\"errorDescription\":\"Invalid fulfilment\"}}";
      forward("PUT", payer + "/transfers/" + transferId + "/error", "transfers", "hub", payer, error);
      forward("PUT", payee + "/transfers/" + transferId + "/error", "transfers", "hub", payee, error);
      return Response.ok().build();
    }
    BigDecimal amount = new BigDecimal(transfer.path("amount").path("amount").asText());
    POSITIONS.merge(payer, amount, BigDecimal::add);
    POSITIONS.merge(payee, amount.negate(), BigDecimal::add);
    String completed = Fspiop.timestamp(Instant.now());
    forward("PUT", payer + "/transfers/" + transferId, "transfers", "hub", payer,
        "{\"fulfilment\":\"" + fulfil.path("fulfilment").asText() + "\",\"completedTimestamp\":\"" + completed
            + "\",\"transferState\":\"COMMITTED\"}");
    if ("RESERVED".equals(fulfil.path("transferState").asText())) {
      forward("PATCH", payee + "/transfers/" + transferId, "transfers", "hub", payee,
          "{\"completedTimestamp\":\"" + completed + "\",\"transferState\":\"COMMITTED\"}");
    }
    return Response.ok().build();
  }

  @PUT
  @Path("/transfers/transfers/{id}/error")
  public Response putTransferError(@PathParam("id") String transferId, String body) {
    FSPIOP_CALLS.add("PUT /transfers/" + transferId + "/error");
    String payer = TRANSFERS.get(transferId).path("payerFsp").asText();
    forward("PUT", payer + "/transfers/" + transferId + "/error", "transfers", "hub", payer, body);
    return Response.ok().build();
  }

  // --- central-ledger admin ---

  @POST
  @Path("/admin/{segments: .+}")
  public Response adminPost(@PathParam("segments") String segments, String body) {
    ADMIN_CALLS.add("POST /" + segments + " " + body);
    return Response.status(Response.Status.CREATED).build();
  }

  @GET
  @Path("/admin/participants")
  @Produces(MediaType.APPLICATION_JSON)
  public String participants() {
    StringBuilder json = new StringBuilder("[");
    for (String fspId : ORACLE.values().stream().distinct().toList()) {
      json.append(json.length() > 1 ? "," : "").append("{\"name\":\"").append(fspId)
          .append("\",\"accounts\":[{\"id\":\"pos-").append(fspId).append("\",\"ledgerAccountType\":\"POSITION\"}]}");
    }
    return json.append("]").toString();
  }

  @GET
  @Path("/admin/participants/{name}/accounts")
  @Produces(MediaType.APPLICATION_JSON)
  public String accounts(@PathParam("name") String name) {
    return "[{\"id\":\"pos-" + name + "\",\"ledgerAccountType\":\"POSITION\",\"currency\":\"CVE\"},"
        + "{\"id\":\"set-" + name + "\",\"ledgerAccountType\":\"SETTLEMENT\",\"currency\":\"CVE\"}]";
  }

  @GET
  @Path("/admin/participants/{name}/positions")
  @Produces(MediaType.APPLICATION_JSON)
  public String positions(@PathParam("name") String name) {
    return "[{\"currency\":\"CVE\",\"value\":" + POSITIONS.getOrDefault(name, BigDecimal.ZERO).toPlainString()
        + "}]";
  }

  // --- central-settlement ---

  @GET
  @Path("/settlement/settlements")
  @Produces(MediaType.APPLICATION_JSON)
  public String settlements() {
    return settlementsJson;
  }

  private static void forward(String method, String path, String resource, String source, String destination,
      String body) {
    EXECUTOR.execute(() -> {
      try {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(SIM + path))
            .header("Content-Type", Fspiop.contentType(resource))
            .header(Fspiop.SOURCE, source)
            .header(Fspiop.DESTINATION, destination)
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding());
      } catch (Exception e) {
        throw new IllegalStateException("Forwarding " + method + " " + path + " failed", e);
      }
    });
  }

}
