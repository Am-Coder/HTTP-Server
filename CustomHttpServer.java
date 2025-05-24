import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CustomHttpServer {
    private static final int PORT = 8080;
    private static final String UPLOADS_DIR = "uploads";
    private static final int THREAD_POOL_SIZE = 10;
    private static final String RESOURCE_LOCATION = "./resource/";

    public static void main(String[] args) {
        try {
            // Create uploads directory if it doesn't exist
            Path uploadDirPath = Paths.get(UPLOADS_DIR);
            if (!Files.exists(uploadDirPath)) {
                Files.createDirectories(uploadDirPath);
            }

            // Create server socket
            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("Server started on port " + PORT);

            // Create thread pool for handling requests
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

            // Server loop
            while (true) {
                try {
                    // Accept incoming connection
                    Socket clientSocket = serverSocket.accept();

                    // Submit request to thread pool
                    executor.submit(new RequestHandler(clientSocket));
                } catch (IOException e) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    static class RequestHandler implements Runnable {
        private final Socket clientSocket;

        public RequestHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try (
                    InputStream inputStream = clientSocket.getInputStream();
                    OutputStream outputStream = clientSocket.getOutputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
            ) {
                System.out.println("Available bytes in stream: " + inputStream.available());
                // Parse HTTP request
                HttpRequest request = parseRequest(reader);
                System.out.println(request.getMethod() + " " + request.getPath());
                // Process request and generate response
                HttpResponse response = processRequest(request);

                // Send response
                response.send(outputStream);

            } catch (Exception e) {
                System.err.println("Error handling request: " + e.getMessage());
                e.printStackTrace();

                // Try to send an error response
                try {
                    String errorMsg = "{\"error\":\"Internal server error: " + e.getMessage() + "\"}";
                    HttpResponse errorResponse = new HttpResponse(500, "Internal Server Error",
                            "application/json", errorMsg.getBytes());
                    errorResponse.send(clientSocket.getOutputStream());
                } catch (IOException ex) {
                    System.err.println("Failed to send error response: " + ex.getMessage());
                }
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
            }
        }

        private HttpRequest parseRequest(BufferedReader reader) throws IOException {
            // Read request line
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                throw new IOException("Empty request");
            }

            // Parse request line
            String[] requestParts = requestLine.split(" ");
            if (requestParts.length != 3) {
                throw new IOException("Invalid request line: " + requestLine);
            }

            String method = requestParts[0];
            String path = requestParts[1];
            String httpVersion = requestParts[2];

            // Parse headers
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                int colonPos = headerLine.indexOf(':');
                if (colonPos > 0) {
                    String headerName = headerLine.substring(0, colonPos).trim();
                    String headerValue = headerLine.substring(colonPos + 1).trim();
                    headers.put(headerName.toLowerCase(), headerValue);
                }
            }

            // Create request object
            HttpRequest request = new HttpRequest(method, path, httpVersion, headers);

            // parse request body
            if (method.equals("POST") || method.equals("PUT")) {
                String contentLengthHeader = headers.get("content-length");
                if (contentLengthHeader != null) {
                    int contentLength = Integer.parseInt(contentLengthHeader);
                    if (contentLength > 0) {
                        // Read the body
                        char[] bodyChars = new char[contentLength];
                        int charsRead = reader.read(bodyChars, 0, contentLength);
                        if (charsRead > 0) {
                            request.setBody(new String(bodyChars, 0, charsRead));
                        }
                    }
                }
            }

            return request;
        }

        private HttpResponse processRequest(HttpRequest request) throws IOException {
            String path = request.getPath();
            String method = request.getMethod();
            // Route request to appropriate handler
            if (path.equals("/")) {
                return handleHomePage();
            } else if (path.startsWith("/api/")) {
                return handleApiRequest(request);
            } else if (path.equals("/upload") && method.equals("POST")) {
                return handleFileUpload(request);
            } else {
                // Not found
                return new HttpResponse(404, "Not Found", "text/plain", "404 Not Found".getBytes());
            }
        }

        private HttpResponse handleHomePage() {
            try {
                return new HttpResponse(200, "OK", "text/html",
                        Files.readAllBytes(Paths.get(RESOURCE_LOCATION + "index.html")));
            } catch (IOException e) {
                return new HttpResponse(200, "OK", "text/html",
                        "Error Rendering Home Page".getBytes());
            }
        }

        private HttpResponse handleApiRequest(HttpRequest request) {
            String path = request.getPath();
            String method = request.getMethod();
            String endpoint = path.substring("/api".length());

            System.out.println("API Request: " + method + " " + endpoint);

            // API endpoints
            if (endpoint.equals("/info") && method.equals("GET")) {
                String response = "{\"status\":\"OK\",\"message\":\"API is running\"}";
                return new HttpResponse(200, "OK", "application/json", response.getBytes());
            } else if (endpoint.equals("/echo") && method.equals("POST")) {
                // Get request body
                String requestBody = request.getBody();
                System.out.println("Request body: " + requestBody);

                if (requestBody == null || requestBody.isEmpty()) {
                    String error = "{\"error\":\"No request body found\"}";
                    return new HttpResponse(400, "Bad Request", "application/json", error.getBytes());
                }

                String response = "{\"echoed\":" + requestBody + "}";
                return new HttpResponse(200, "OK", "application/json", response.getBytes());
            } else {
                String error = "{\"error\":\"Endpoint not found or method not supported\"}";
                return new HttpResponse(404, "Not Found", "application/json", error.getBytes());
            }
        }

        private HttpResponse handleFileUpload(HttpRequest request) {
            // Get content length from headers
            String contentLengthHeader = request.getHeaders().get("content-length");
            if (contentLengthHeader == null) {
                String error = "{\"error\":\"Content-Length header is required for file upload\"}";
                return new HttpResponse(400, "Bad Request", "application/json", error.getBytes());
            }

            int contentLength;
            try {
                contentLength = Integer.parseInt(contentLengthHeader);
            } catch (NumberFormatException e) {
                String error = "{\"error\":\"Invalid Content-Length header\"}";
                return new HttpResponse(400, "Bad Request", "application/json", error.getBytes());
            }

            if (contentLength <= 0) {
                String error = "{\"error\":\"No file content to upload\"}";
                return new HttpResponse(400, "Bad Request", "application/json", error.getBytes());
            }

            // Generate unique filename
            String fileName = UUID.randomUUID().toString();

            // Get file extension from Content-Type if available
            String contentType = request.getHeaders().get("content-type");
            if (contentType != null) {
                String extension = getExtensionFromContentType(contentType);
                if (extension != null) {
                    fileName += extension;
                }
            }

            String filePath = UPLOADS_DIR + File.separator + fileName;
            try (FileOutputStream fileOutputStream = new FileOutputStream(filePath)) {
                fileOutputStream.write(request.body.getBytes());
                String response = "{\"status\":\"success\",\"fileName\":\"" + fileName + "\",\"size\":" + request.body.getBytes().length + "}";
                return new HttpResponse(200, "OK", "application/json", response.getBytes());
            } catch (Exception e) {
                System.err.println("Error saving uploaded file: " + e.getMessage());
                String error = "{\"error\":\"Failed to save uploaded file: " + e.getMessage() + "\"}";
                return new HttpResponse(500, "Internal Server Error", "application/json", error.getBytes());
            }
        }

        // Helper method to get file extension from content type
        private String getExtensionFromContentType(String contentType) {
            return switch (contentType.toLowerCase()) {
                case "text/plain", "text/plain; charset=utf-8" -> ".txt";
                case "application/json" -> ".json";
                case "application/xml", "text/xml" -> ".xml";
                case "text/html" -> ".html";
                case "text/css" -> ".css";
                case "application/javascript", "text/javascript" -> ".js";
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/gif" -> ".gif";
                case "image/webp" -> ".webp";
                case "application/pdf" -> ".pdf";
                case "application/zip" -> ".zip";
                case "application/msword" -> ".doc";
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
                default -> null; // No extension for unknown types
            };
        }
    }

    static class HttpRequest {
        private final String method;
        private final String path;
        private final String httpVersion;
        private final Map<String, String> headers;
        private String body;

        public HttpRequest(String method, String path, String httpVersion, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.httpVersion = httpVersion;
            this.headers = headers;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public String getHttpVersion() {
            return httpVersion;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }
    }

    static class HttpResponse {
        private final int statusCode;
        private final String statusMessage;
        private final String contentType;
        private final byte[] body;
        private final Map<String, String> headers;

        public HttpResponse(int statusCode, String statusMessage, String contentType, byte[] body) {
            this.statusCode = statusCode;
            this.statusMessage = statusMessage;
            this.contentType = contentType;
            this.body = body;
            this.headers = new HashMap<>();

            // Add CORS headers to allow testing from different origins
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Content-Type, Content-Length");
        }

        public void addHeader(String name, String value) {
            headers.put(name, value);
        }

        public void send(OutputStream outputStream) throws IOException {
            // Build response headers
            StringBuilder responseBuilder = new StringBuilder();
            responseBuilder.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append("\r\n");
            responseBuilder.append("Content-Type: ").append(contentType).append("\r\n");
            responseBuilder.append("Content-Length: ").append(body.length).append("\r\n");

            // Add custom headers
            for (Map.Entry<String, String> header : headers.entrySet()) {
                responseBuilder.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }

            // Add empty line to indicate end of headers
            responseBuilder.append("\r\n");

            // Send headers
            outputStream.write(responseBuilder.toString().getBytes(StandardCharsets.UTF_8));

            // Send body
            outputStream.write(body);
            outputStream.flush();
        }
    }
}