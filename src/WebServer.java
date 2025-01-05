import com.google.gson.JsonParser;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServer {
   
   int PORT = 8000;
   private static final int THREAD_POOL_SIZE = 10; // Tamaño del pool de hilos
   private ServerSocket serverSocket;
   private ExecutorService threadPool;
   
   // Tabla de mime types
   private static final Map<String, String> MIME_TYPES = new HashMap<>() {{
      put("txt", "text/plain");
      put("html", "text/html");
      put("htm", "text/html");
      put("css", "text/css");
      put("js", "text/javascript");
      put("json", "application/json");
      put("xml", "application/xml");
      put("jpg", "image/jpeg");
      put("jpeg", "image/jpeg");
      put("png", "image/png");
      put("gif", "image/gif");
      put("ico", "image/x-icon");
      put("pdf", "application/pdf");
      put("zip", "application/zip");
      put("doc", "application/msword");
      put("xls", "application/vnd.ms-excel");
      put("ppt", "application/vnd.ms-powerpoint");
      put("mp3", "audio/mpeg");
      put("tex", "application/x-tex");
   }};
   
   // Tabla de códigos de estado HTTP y sus mensajes
   private static final Map<Integer, String> HTTP_STATUS_CODES = new HashMap<>() {{
      put(200, "OK");
      put(301, "Moved Permanently");
      put(302, "Found");
      put(400, "Bad Request");
      put(404, "Not Found");
      put(405, "Method Not Allowed");
   }};
   
   
   class Handler implements Runnable {
      
      protected Socket socket;
      DataOutputStream dataOutput;
      DataInputStream dataInput;
      
      // asignar el socket recibido a la variable socket del objeto
      public Handler(Socket _socket) {
         this.socket = _socket;
      }
      
      public void run() {
         try {
            dataOutput = new DataOutputStream(socket.getOutputStream());
            dataInput = new DataInputStream(socket.getInputStream());
            
            ByteArrayOutputStream auxBuffer = new ByteArrayOutputStream();
            ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[65535];
            int bytesRead = 0;
            int totalBytesReceived = 0;
            
            socket.setSoTimeout(3000);
            try {
               while (true) {
                  bytesRead = dataInput.read(buffer);
                  auxBuffer.write(buffer, 0, bytesRead);
                  totalBytesReceived += bytesRead;
               }
            } catch (Exception e) {
               System.out.println("Tiempo de espera alcanzado. Finalizando recepción.");
            }
            
            // Como postman (y algunos navegadores) envían una petición adicional por el keep-alive la desactivamos por ahora
            if (totalBytesReceived < 1) {
               System.out.println("Conexión de keep-alive detectada. Cerrando conexión sin datos...");
               socket.close();
               return;
            }
            
            // Copiamos el cuerpo de la petición a un buffer separado, se usa en POST si contiene un archivo
            bodyBuffer.write(auxBuffer.toByteArray(), auxBuffer.toString(StandardCharsets.UTF_8).lastIndexOf("\r\n\r\n") + 4, totalBytesReceived - auxBuffer.toString(StandardCharsets.UTF_8).lastIndexOf("\r\n\r\n") - 4);
            
            // Convertimos los bytes recibidos a una cadena
            String request = auxBuffer.toString(StandardCharsets.UTF_8);
            
            System.out.println("Tamaño de la petición: " + totalBytesReceived + " bytes");
            System.out.println("Petición recibida: \n" + "\u001B[33m" + request + "\u001B[0m");
            System.out.println("Ejecutando en el hilo: " + Thread.currentThread().getName());
            
            // Obtenemos las partes de la petición HTTP (cabeceras y cuerpo)
            String[] requestParts = request.split("\r\n");
            
            // Dividimos la primera línea en sus partes (metodo, recurso y protocolo)
            String[] firstHeadParts = requestParts[0].split(" ");
            
            // Si la petición no tiene 3 partes, entonces es una solicitud HTTP mal formada
            if (firstHeadParts.length != 3) {
               System.err.println("Solicitud HTTP mal formada.");
               
               String badRequestResponse = CreateHead(400, "text/plain", 0);
               badRequestResponse += "Solicitud HTTP mal formada";
               
               dataOutput.write(badRequestResponse.getBytes(StandardCharsets.UTF_8));
               return;
            }
            
            // Obtenemos el metodo, el recurso y el cuerpo de la petición HTTP (si lo tiene).
            // El recurso y el cuerpo de la petición se decodifican para evitar problemas con los espacios y caracteres especiales.
            String method = firstHeadParts[0].toUpperCase();
            String resource = URLDecoder.decode(firstHeadParts[1], StandardCharsets.UTF_8);
            
            String responseForClient = "";
            
            switch (method) {
               case "GET":
                  responseForClient = GETHandler(resource, dataOutput);
                  break;
               
               case "POST":
                  responseForClient = POSTHandler(request, bodyBuffer, resource);
                  break;
               
               case "PUT":
                  responseForClient = "HTTP/1.1 200 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: 27\r\n"
                          + "\r\n"
                          + "Hello World! desde un PUT";
                  break;
               
               case "DELETE":
                  responseForClient = "HTTP/1.1 200 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: 30\r\n"
                          + "\r\n"
                          + "Hello World! desde un DELETE";
                  break;
               
               default:
                  responseForClient = "HTTP/1.1 405 Method Not Allowed\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: 22\r\n"
                          + "\r\n"
                          + "Método no permitido";
                  break;
            }

            dataOutput.write(responseForClient.getBytes(StandardCharsets.UTF_8));
            dataOutput.flush();
            
         } catch (IOException e) {
            throw new RuntimeException(e);
         } finally {
            try {
               dataOutput.close();
               socket.close();
            } catch (IOException e) {
               e.printStackTrace();
            }
         }
      }
   }
   
   // Metodo para obtener parametros de una petición. Recibe una cadena de formato "nombre=valor&nombre2=valor2"
   public Map<String, String> getParameters(String parameters) {
      Map<String, String> params = new HashMap<>();
      
      String[] pairs = parameters.split("&");
      
      for (String pair : pairs) {
         String[] keyValue = pair.split("=");
         
         // Si el parámetro tiene valor en cierta key, se agrega al mapa
         // Si no tiene valor, se agrega a su key una cadena vacía
         if (keyValue.length == 2) {
            params.put(keyValue[0], keyValue[1]);
         } else {
            params.put(keyValue[0], "");
         }
      }
      
      return params;
   }
   
   public String GETHandler(String resource, DataOutputStream dataOutput) {
      String response = "";
      String bodyResponse = "";
      
      // Si la petición contiene parámetros
      if (resource.contains("?")) {
         System.out.println("Petición con parámetros");
         resource = resource.substring(resource.indexOf("?") + 1);   // Eliminar el recurso de la petición
         
         // Obtener los parámetros de la petición
         Map<String, String> parameters = getParameters(resource);
         
         // Agregar los parámetros al cuerpo de la respuesta
         for (Map.Entry<String, String> entry : parameters.entrySet()) {
            //System.out.println("Parámetro: " + entry.getKey() + " Tiene: " + entry.getValue());
            bodyResponse += entry.getKey() + ": " + entry.getValue() + "\n";
         }
         
         // Crear la respuesta HTTP
         bodyResponse = DeleteAcents(bodyResponse);
         response = CreateHead(200, "text/plain", bodyResponse.length());
         response += bodyResponse;
         return response;
      }
      
      // Si no hay parámetros, se envía el archivo solicitado o el index.html
      if (resource.equals("/") || resource.equals("/index.html") || resource.equals("/index.htm") || resource == null) {
         // Enviar el archivo index.html
         SendFile("index.html", dataOutput);

      } else {
         resource = resource.substring(1); // Eliminar la barra inicial
         System.out.println("Recurso solicitado: " + resource);
         File file = new File(resource);
         
         // si el archivo existe y el ultimo caracter del recurso es No es un slash entonces se envia el archivo
         if (file.exists() && file.isFile() && resource.charAt(resource.length() - 1) != '/') {
            // Enviar el archivo
            SendFile(resource, dataOutput);
            
         } else if (file.exists() && file.isDirectory() && resource.charAt(resource.length() - 1) == '/') { // Si el recurso es un directorio y termina en /
            // Obtener la lista de archivos del directorio
            String[] fileNames = file.list();
            
            bodyResponse += "Archivos en el directorio:\n";
            for (String fileName : fileNames) {
               bodyResponse += fileName + "\n";
            }
            
            bodyResponse = DeleteAcents(bodyResponse);
            response = CreateHead(200, "text/plain", bodyResponse.length());
            response += bodyResponse;
            
         } else if (file.exists() && file.isDirectory() && resource.charAt(resource.length() - 1) != '/') { // Si el recurso es un directorio y no termina en /
            // Simulación de redireccionamiento
            response = CreateHeadRedirect(301, "text/plain", 0, resource);
         } else {
            System.out.println("Archivo no encontrado: " + file.getName());
            bodyResponse = "Archivo o recurso no encontrado";
            response = CreateHead(404, "text/plain", bodyResponse.length());
            response += bodyResponse;
         }
      }
      
      //System.out.println("Respuesta:" + response);
      return response;
   }
   
   // La petición HTTP POST se utiliza para enviar datos al servidor para que procese una acción específica. Ej:
   // Enviar datos de un formulario HTML al servidor, agregar un nuevo registro a una base de datos, realizar un pago, autenticar a un usuario, etc.
   public String POSTHandler(String request, ByteArrayOutputStream bodyBuffer, String resource) {
      String[] requestParts = request.split("\r\n");
      int contentLength = 0;
      String bodyRequest = "";
      String contentType = "";
      String response = "";
      String boundary = "";   // Si la petición es de tipo multipart/form-data
      Map<String, String> parameters;
      
      // Buscar la cabecera Content-Length en la petición HTTP
      for (String part : requestParts) {
         if (part.contains("Content-Length")) contentLength = Integer.parseInt(part.split(":" )[1].trim());
         if (part.contains("Content-Type")) contentType = part.split(":")[1].trim();
      }
      
      // Si la petición es de tipo multipart/form-data, buscar el boundary
      if (contentType.contains("multipart/form-data")) {
         boundary = contentType.split("boundary=")[1];
         contentType = "multipart/form-data";
      }
      
      System.out.println("Content-Length: " + contentLength);
      System.out.println("Content-Type: " + contentType);
      
      // Si la petición tiene cuerpo crear una respuesta según el tipo de contenido
      if (contentLength > 0) {
         switch (contentType) {
            case "application/x-www-form-urlencoded":
               // Extraer los parámetros del cuerpo de la petición y agregarlos al cuerpo de la respuesta
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               parameters = getParameters(bodyRequest);
               
               bodyRequest = "";
               for (Map.Entry<String, String> entry : parameters.entrySet()) {
                  bodyRequest += entry.getKey() + ": " + entry.getValue() + "\n";
               }
               
               if (!resource.equals("/")) {
                  int statusUpdateForm = updateFormSimulation(resource.substring(1), parameters);
                  if (statusUpdateForm == 200) {
                     bodyRequest = "Formulario actualizado";
                     response = CreateHead(200, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  } else {
                     bodyRequest = "Formulario no encontrado";
                     response = CreateHead(404, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  }
                  break;
               } else {
                  bodyRequest = DeleteAcents(bodyRequest);
                  response = CreateHead(200, "text/plain", bodyRequest.length());
                  response += bodyRequest;
                  break;
               }
               
            case "multipart/form-data":
               // Construir una cadena en formato nombre=valor&nombre2=valor2
               String[] parts = request.split("--" + boundary);
               
               for (String part : parts) {
                  if (part.contains("Content-Disposition")) {
                     String[] disposition = part.split("\r\n");
                     String name = disposition[1].split("name=\"")[1].split("\"")[0];
                     String value = part.substring(part.indexOf("\r\n\r\n") + 4, part.lastIndexOf("\r\n"));
                     bodyRequest += name + "=" + value + "&";
                  }
               }
               bodyRequest = bodyRequest.substring(0, bodyRequest.length() - 1); // Eliminar el último &
               
               parameters = getParameters(bodyRequest);
               
               bodyRequest = "";
               for (Map.Entry<String, String> entry : parameters.entrySet()) {
                  bodyRequest += entry.getKey() + ": " + entry.getValue() + "\n";
               }
               
               if (!resource.equals("/")) {
                  int statusUpdateForm = updateFormSimulation(resource.substring(1), parameters);  // Eliminar la barra inicial
                  if (statusUpdateForm == 200) {
                     bodyRequest = "Formulario actualizado";
                     response = CreateHead(200, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  } else {
                     bodyRequest = "Formulario no encontrado";
                     response = CreateHead(404, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  }
                  break;
               } else {
                  bodyRequest = DeleteAcents(bodyRequest);
                  response = CreateHead(200, "text/plain", bodyRequest.length());
                  response += bodyRequest;
                  break;
               }
            
            
            case "application/json":
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               if (isValidJson(bodyRequest)) {
                  bodyRequest = DeleteAcents(bodyRequest);
                  response = CreateHead(200, "application/json", bodyRequest.length());
                  response += bodyRequest;
                  
                  // Guardar el archivo JSON en el servidor
                  saveFile("archivo" + System.currentTimeMillis() + ".json", bodyRequest.getBytes());
                  
               } else {
                  bodyRequest = "JSON mal formado";
                  response = CreateHead(400, "text/plain", bodyRequest.length());
                  response += bodyRequest;
               }
               break;
               
            case "application/xml":
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               if (isValidXml(bodyRequest)) {
                  bodyRequest = DeleteAcents(bodyRequest);
                  response = CreateHead(200, "application/xml", bodyRequest.length());
                  response += bodyRequest;
               } else {
                  bodyRequest = "XML mal formado";
                  response = CreateHead(400, "text/plain", bodyRequest.length());
                  response += bodyRequest;
               }
               break;
               
            case "text/html":
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               if (isValidHtml(bodyRequest)) {
                  bodyRequest = DeleteAcents(bodyRequest);
                  response = CreateHead(200, "text/html", bodyRequest.length());
                  response += bodyRequest;
               } else {
                  bodyRequest = "HTML mal formado";
                  response = CreateHead(400, "text/plain", bodyRequest.length());
                  response += bodyRequest;
               }
               break;
               
            case "text/plain":
               if (!resource.equals("/")) {
                  int statusUpdateFile = updateFileText(resource.substring(1), URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8));
                  if (statusUpdateFile == 200) {
                     bodyRequest = "Archivo actualizado";
                     response = CreateHead(200, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  } else {
                     bodyRequest = "Archivo no encontrado";
                     response = CreateHead(404, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  }
                  break;
               } else {
                  bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
                  bodyRequest = DeleteAcents(bodyRequest);
                  response = CreateHead(200, "text/plain", bodyRequest.length());
                  response += bodyRequest;
                  break;
               }
               
               
            default:
               bodyRequest = "Como el tipo de contenido no es soportado, se almacenara en el servidor";
               saveFile("archivo_" + System.currentTimeMillis() + "." + getKeyByValue(MIME_TYPES, contentType), bodyBuffer.toByteArray());
               response = CreateHead(200, "text/plain", bodyRequest.length());
               response += bodyRequest;
               break;
         }
      } else {
         bodyRequest = "Peticion POST sin cuerpo";
         response = CreateHead(400, "text/plain", bodyRequest.length());
         response += bodyRequest;
      }

      return response;
   }
   
   // Metodo para eliminar acentos y caracteres especiales de una cadena de texto. Util para evitar problemas con el envio de respuestas HTTP
   public String DeleteAcents(String text) {
      return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "");
   }
   
   // Metodo para crear una respuesta HTTP (cabecera)
   public String CreateHead(int statusCode, String mimeType, long fileSize) {
      return "HTTP/1.1 " + statusCode + " " + HTTP_STATUS_CODES.get(statusCode) + "\r\n"
              + "Server: Hervert Server/1.0\r\n"
              + "Date: " + new Date() + "\r\n"
              + "Content-Type: " + mimeType + "\r\n"
              + "Content-Length: " + fileSize + "\r\n"
              + "Connection: close\r\n"
              + "\r\n";
   }
   
   // Metodo para crear una respuesta HTTP (cabecera) para redireccionamiento
   public String CreateHeadRedirect(int statusCode, String mimeType, long fileSize, String location) {
         return "HTTP/1.1 " + statusCode + " " + HTTP_STATUS_CODES.get(statusCode) + "\r\n"
               + "Server: Hervert Server/1.0\r\n"
               + "Date: " + new Date() + "\r\n"
               + "Content-Type: " + mimeType + "\r\n"
               + "Content-Length: " + fileSize + "\r\n"
               + "Location: " + location + "/\r\n"
               + "Connection: close\r\n"
               + "\r\n";
   }
   
   // Metodo para enviar un archivo al cliente (GET)
   public void SendFile (String fileToSend, DataOutputStream dataOutput) {
      try {
         int bytesRead = 0;
         byte[] buffer = new byte[1024];
         
         DataInputStream fileInput = new DataInputStream(new FileInputStream(fileToSend));
         File file = new File(fileToSend);
         
         // Obtener el nombre y la extensión del archivo, además del mime type
         String[] fileNameAndExtension = fileToSend.split("\\.");
         String fileName = fileNameAndExtension[0];
         String fileExtension = fileNameAndExtension[1];
         String mimeType = MIME_TYPES.get(fileExtension);
         
         System.out.println("fileName = " + fileName);
         System.out.println("fileExtension = " + fileExtension);
         System.out.println("Archivo encontrado: " + file.getName());
         System.out.println("Mime type: " + mimeType);
         
         // Crear la respuesta HTTP
         String response = CreateHead(200, mimeType, file.length());
         
         // Enviar la respuesta HTTP sin el archivo
         dataOutput.write(response.getBytes(StandardCharsets.UTF_8));
         dataOutput.flush();
         
         // Enviar el archivo en bloques de 1024 bytes
         while ((bytesRead = fileInput.read(buffer)) != -1) {
            dataOutput.write(buffer, 0, bytesRead);
            dataOutput.flush();
         }
         
         dataOutput.close();
         fileInput.close();
         
      } catch (IOException e) {
         e.printStackTrace();
      }
   }
   
   public void saveFile(String fileName, byte[] fileBytes) {
      try (FileOutputStream fileOutput = new FileOutputStream(fileName)) {
         fileOutput.write(fileBytes);
      } catch (IOException e) {
         e.printStackTrace();
      }
   }
   
   public int updateFormSimulation(String form, Map<String, String> parameters) {
      // Abrir el archivo form.txt y gaurdar su contenido en una cadena
      String formFileName = form + ".txt";
      File file = new File(formFileName);
      
      if (file.exists()) {
         // Leer el contenido del archivo
         String formContent = "";
         Map<String, String> formParameters = new HashMap<>();
         
         try (BufferedReader reader = new BufferedReader(new FileReader(formFileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
               formContent += line + "\n";
               String[] keyValue = line.split(":");
               
               if (keyValue.length == 2) {
                  formParameters.put(keyValue[0].trim(), keyValue[1].trim());
               } else {
                  formParameters.put(keyValue[0], "");
               }
            }
         } catch (IOException e) {
            e.printStackTrace();
         }
         
         System.out.println("\nParámetros del formulario existente:");
         for (Map.Entry<String, String> entry : formParameters.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
         }

         System.out.println("\nParámetros del formulario entrante:");
         for (Map.Entry<String, String> entry : parameters.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
         }

         // Actualizar los parámetros del formulario existente con los nuevos parámetros
         for (Map.Entry<String, String> entry : parameters.entrySet()) {
            formParameters.put(entry.getKey(), entry.getValue());
         }

         System.out.println("\nParámetros del formulario actualizado:");
         for (Map.Entry<String, String> entry : formParameters.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
         }
         
         // Guardar los parámetros actualizados en el archivo
         try (BufferedWriter writer = new BufferedWriter(new FileWriter(formFileName))) {
            for (Map.Entry<String, String> entry : formParameters.entrySet()) {
               writer.write(entry.getKey() + ": " + entry.getValue() + "\n");
            }
         } catch (IOException e) {
            e.printStackTrace();
         }
         return 200;
      } else {
         return 404;
      }
   }
   
   public int updateFileText(String fileName, String text) {
      // Actualizar el contenido de un archivo solo si existe y es de texto
      File file = new File(fileName);
      
      if (file.exists() && file.isFile() && fileName.contains(".txt")) {
         try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            writer.write(text);
         } catch (IOException e) {
            e.printStackTrace();
         }
         return 200;
      } else {
         return 404;
      }
   }

   // Metodo para obtener la clave de un valor en un mapa, util para extraer la extensión de un archivo a partir de su mime type
   public static String getKeyByValue(Map<String, String> map, String value) {
      for (Map.Entry<String, String> entry : map.entrySet()) {
         if (entry.getValue().equals(value)) {
            return entry.getKey();
         }
      }
      return "";  // Si no se encuentra el valor, se retorna una cadena vacía
   }
   
   public static boolean isValidJson(String json) {
      try {
         JsonParser.parseString(json);
         return true;
      } catch (Exception e) {
         return false;
      }
   }
   
   public static boolean isValidHtml(String html) {
      try {
         return html.contains("!DOCTYPE html") &&
                html.contains("<html") &&
                html.contains("<head>") &&
                html.contains("<body>") &&
                html.contains("</body>") &&
                html.contains("</head>") &&
                html.contains("</html>");
      } catch (Exception e) {
         return false;
      }
   }
   
   public static boolean isValidXml(String xml) {
      try {
         DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
         DocumentBuilder builder = factory.newDocumentBuilder();
         builder.parse(new ByteArrayInputStream(xml.getBytes()));
         return true;
      } catch (Exception e) {
         return false;
      }
   }
   
   // Constructor
   public WebServer() throws IOException {
      System.out.println("\u001B[32mIniciando servidor web...\u001B[0m");
      
      // Crear el socket del servidor y el pool de hilos
      this.serverSocket = new ServerSocket(PORT);
      this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
      
      System.out.println("Servidor web iniciado en el puerto \u001B[32m" + PORT + "\u001B[0m");
      System.out.println("\u001B[34mEsperando conexiones...\n\u001B[0m");
      
      while (true) {
         Socket socket = serverSocket.accept();
         System.out.println("Conexión aceptada desde \u001B[35m" + socket.getInetAddress() + "\u001B[0m");
         
         // Asigna la conexión aceptada a un hilo del pool
         threadPool.execute(new Handler(socket));
      }
   }
   
   public static void main(String[] args) throws IOException {
      new WebServer();
   }
}
