import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;

public class ChatServer {

    static ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/chat", (HttpExchange exchange) -> {
            try {
                // Allow any origin to call this server
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

                // The browser sends this BEFORE the real POST, just to check permissions
                if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                // Read the request body
                InputStream is = exchange.getRequestBody();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                is.transferTo(buffer);
                String rawBody = buffer.toString();

                // Parse the incoming JSON
                JsonNode requestNode = mapper.readTree(rawBody);

                // Extract message
                String userMessage = "";
                if (requestNode.has("message") && !requestNode.get("message").isNull()) {
                    userMessage = requestNode.get("message").asText();
                }

                // Extract model with fallback default to llama3.2:1b (#15)
                String selectedModel = "llama3.2:1b";
                if (requestNode.has("model") && !requestNode.get("model").isNull() && !requestNode.get("model").asText().isBlank()) {
                    selectedModel = requestNode.get("model").asText();
                }

                System.out.println("Model: " + selectedModel + " | Message: " + userMessage);

                // Build the request body for Ollama using the selected model
                Map<String, Object> ollamaRequestMap = new HashMap<>();
                ollamaRequestMap.put("model", selectedModel);
                ollamaRequestMap.put("prompt", userMessage);
                ollamaRequestMap.put("stream", false);
                String ollamaRequestBody = mapper.writeValueAsString(ollamaRequestMap);

                // Send request to Ollama
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest ollamaRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(ollamaRequestBody))
                    .build();

                HttpResponse<String> ollamaResponse = client.send(ollamaRequest, HttpResponse.BodyHandlers.ofString());

                // Parse Ollama's response
                JsonNode rootNode = mapper.readTree(ollamaResponse.body());
                String aiReply = rootNode.has("response") ? rootNode.get("response").asText() : "No response generated.";

                System.out.println("AI reply: " + aiReply);

                // Send back JSON response
                Map<String, String> finalResponse = new HashMap<>();
                finalResponse.put("reply", aiReply);
                String responseJson = mapper.writeValueAsString(finalResponse);

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] responseBytes = responseJson.getBytes("UTF-8");
                exchange.sendResponseHeaders(200, responseBytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(responseBytes);
                os.close();

            } catch (Exception e) {
                e.printStackTrace();
                try {
                    String errorMsg = "{\"error\": \"Something went wrong\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    byte[] errorBytes = errorMsg.getBytes("UTF-8");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(errorBytes);
                    os.close();
                } catch (Exception ignored) {}
            }
        });

        server.start();
        System.out.println("Server is running on http://localhost:8080/chat");
    }
}