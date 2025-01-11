import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServer {
   
   int PORT = 8000;
   private static final int THREAD_POOL_SIZE = 10; // Tamaño del pool de hilos
   private ServerSocketChannel serverSocketChannel;
   private ExecutorService threadPool;
   
   private Selector selector;
   private int lecturas = 0;
   private int escrituras = 0;
   
   // Tabla de mime types y tabla de códigos de estado HTTP y sus mensajes
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
   private static final Map<Integer, String> HTTP_STATUS_CODES = new HashMap<>() {{
      put(200, "OK");
      put(301, "Moved Permanently");
      put(302, "Found");
      put(400, "Bad Request");
      put(403, "Forbidden");
      put(404, "Not Found");
      put(405, "Method Not Allowed");
   }};
   
   
   class Handler implements Runnable {
      private final SocketChannel clientChannel;
      SelectionKey key;
      DataOutputStream dataOutput;
      DataInputStream dataInput;
      
      public Handler(SocketChannel clientChannel, SelectionKey key) {
         this.clientChannel = clientChannel;
         this.key = key;
      }
      
      public void run() {
         try {
            System.out.println("Ejecutando en el hilo: " + Thread.currentThread().getName());
            dataOutput = new DataOutputStream(clientChannel.socket().getOutputStream());
            dataInput = new DataInputStream(clientChannel.socket().getInputStream());
            
            ByteArrayOutputStream auxBuffer = new ByteArrayOutputStream();
            ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[65535];
            int bytesRead = 0;
            int totalBytesReceived = 0;
            
            // Establecer un tiempo de espera para la recepción de datos
            clientChannel.socket().setSoTimeout(3000);
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
               dataOutput.close();
               clientChannel.close();
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
               
               String badRequestResponse = createHead(400, "text/plain", 0);
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
                  responseForClient = getHandler(resource, dataOutput);
                  break;
               
               case "POST":
                  responseForClient = postHandler(request, bodyBuffer, resource);
                  break;
               
               case "PUT":
                  responseForClient = putHandler(request, resource, bodyBuffer);
                  break;
               
               case "DELETE":
                  responseForClient = deleteHandler(resource);
                  break;
                  
               case "HEAD":
                  responseForClient = headHandler(resource);
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
               dataInput.close();
               clientChannel.close();
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
   
   public String getHandler(String resource, DataOutputStream dataOutput) {
      String response = "";
      String bodyResponse = "";
      
      // Si la petición contiene parámetros
      if (resource.contains("?")) {
         System.out.println("Petición con parámetros");
         resource = resource.substring(resource.indexOf("?") + 1);   // Eliminar el recurso de la petición y obtener solo los parámetros
         
         // Obtener los parámetros de la petición
         Map<String, String> parameters = getParameters(resource);
         
         // Agregar los parámetros al cuerpo de la respuesta
         for (Map.Entry<String, String> entry : parameters.entrySet()) {
            //System.out.println("Parámetro: " + entry.getKey() + " Tiene: " + entry.getValue());
            bodyResponse += entry.getKey() + ": " + entry.getValue() + "\n";
         }
         
         // Crear la respuesta HTTP
         bodyResponse = deleteAcents(bodyResponse);
         response = createHead(200, "text/plain", bodyResponse.length());
         response += bodyResponse;
         return response;
      }
      
      // Si no hay parámetros, se envía el archivo solicitado o el index.html
      if (resource.equals("/") || resource.equals("/index.html") || resource.equals("/index.htm") || resource == null) {
         // Enviar el archivo index.html
         sendFile("index.html", dataOutput);

      } else {
         resource = resource.substring(1); // Eliminar la barra inicial
         System.out.println("Recurso solicitado: " + resource);
         File file = new File(resource);
         
         // si el archivo existe y el ultimo caracter del recurso es No es un slash entonces se envia el archivo
         if (file.exists() && file.isFile() && resource.charAt(resource.length() - 1) != '/') {
            // Enviar el archivo
            sendFile(resource, dataOutput);
            
         } else if (file.exists() && file.isDirectory() && resource.charAt(resource.length() - 1) == '/') { // Si el recurso es un directorio y termina en /
            // Obtener la lista de archivos del directorio
            String[] fileNames = file.list();
            
            bodyResponse += "Archivos en el directorio:\n";
            for (String fileName : fileNames) {
               bodyResponse += fileName + "\n";
            }
            
            bodyResponse = deleteAcents(bodyResponse);
            response = createHead(200, "text/plain", bodyResponse.length());
            response += bodyResponse;
            
         } else if (file.exists() && file.isDirectory() && resource.charAt(resource.length() - 1) != '/') { // Si el recurso es un directorio y no termina en /
            // Simulación de redireccionamiento
            response = createHeadRedirect(301, "text/plain", 0, resource);
         } else {
            System.out.println("Archivo no encontrado: " + file.getName());
            bodyResponse = "Archivo o recurso no encontrado";
            response = createHead(404, "text/plain", bodyResponse.length());
            response += bodyResponse;
         }
      }
      
      //System.out.println("Respuesta:" + response);
      return response;
   }
   
   // La petición HTTP POST se utiliza para enviar datos al servidor para que procese una acción específica. Ej:
   // Enviar datos de un formulario HTML al servidor, agregar un nuevo registro a una base de datos, realizar un pago, autenticar a un usuario, etc.
   public String postHandler(String request, ByteArrayOutputStream bodyBuffer, String resource) {
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
                     response = createHead(200, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  } else {
                     bodyRequest = "Formulario no encontrado";
                     response = createHead(404, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  }
                  break;
               } else {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "text/plain", bodyRequest.length());
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
                     response = createHead(200, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  } else {
                     bodyRequest = "Formulario no encontrado";
                     response = createHead(404, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  }
                  break;
               } else {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "text/plain", bodyRequest.length());
                  response += bodyRequest;
                  break;
               }
            
            
            case "application/json":
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               if (isValidJson(bodyRequest)) {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "application/json", bodyRequest.length());
                  response += bodyRequest;
                  
                  // Guardar el archivo JSON en el servidor
                  saveFile("archivo" + System.currentTimeMillis() + ".json", bodyRequest.getBytes());
                  
               } else {
                  bodyRequest = "JSON mal formado";
                  response = createHead(400, "text/plain", bodyRequest.length());
                  response += bodyRequest;
               }
               break;
               
            case "application/xml":
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               if (isValidXml(bodyRequest)) {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "application/xml", bodyRequest.length());
                  response += bodyRequest;
               } else {
                  bodyRequest = "XML mal formado";
                  response = createHead(400, "text/plain", bodyRequest.length());
                  response += bodyRequest;
               }
               break;
               
            case "text/html":
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               if (isValidHtml(bodyRequest)) {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "text/html", bodyRequest.length());
                  response += bodyRequest;
               } else {
                  bodyRequest = "HTML mal formado";
                  response = createHead(400, "text/plain", bodyRequest.length());
                  response += bodyRequest;
               }
               break;
               
            case "text/plain":
               if (!resource.equals("/")) {
                  int statusUpdateFile = updateFileText(resource.substring(1), URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8), false);
                  if (statusUpdateFile == 200) {
                     bodyRequest = "Archivo actualizado";
                     response = createHead(200, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  } else {
                     bodyRequest = "Archivo no encontrado";
                     response = createHead(404, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  }
                  break;
               } else {
                  bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "text/plain", bodyRequest.length());
                  response += bodyRequest;
                  break;
               }
               
               
            default:
               bodyRequest = "Como el tipo de contenido no es soportado, se almacenara en el servidor";
               saveFile("archivo_" + System.currentTimeMillis() + "." + getKeyByValue(MIME_TYPES, contentType), bodyBuffer.toByteArray());
               response = createHead(200, "text/plain", bodyRequest.length());
               response += bodyRequest;
               break;
         }
      } else {
         bodyRequest = "Peticion POST sin cuerpo";
         response = createHead(400, "text/plain", bodyRequest.length());
         response += bodyRequest;
      }

      return response;
   }
   
   // La petición HTTP PUT se utiliza para enviar datos al servidor para que procese una acción específica, es una petición idempotente.
   // Generalmente, la URL (o URI) especificada en la petición indica la ubicación exacta del recurso que se está creando o actualizando.
   // Los datos enviados al servidor, usualmente en el cuerpo de la solicitud, deben estar en un formato que el servidor pueda interpretar, json por ejemplo.
   // Ej: Actualizar un recurso, reemplazar un recurso, crear un recurso si no existe, etc.
   public String putHandler(String request, String resource, ByteArrayOutputStream bodyBuffer) {
      String response = "";
      String bodyRequest = "";
      
      // Si la petición no tiene cuerpo se envía un mensaje de error
      if (bodyBuffer.size() < 1) {
         bodyRequest = "Peticion PUT sin cuerpo";
         response = createHead(400, "text/plain", bodyRequest.length());
         response += bodyRequest;
         return response;
      }
      
      // Extraemos el recurso solicitado
      resource = resource.substring(1);
      System.out.println("resource = " + resource);
      
      // si el recurso es un directorio se envía un mensaje de error
      File fileResource = new File(resource);
      if (fileResource.isDirectory()) {
         bodyRequest = "No se puede actualizar un directorio";
         response = createHead(400, "text/plain", bodyRequest.length());
         response += bodyRequest;
         return response;
      }
      
      // Extraer el Content-Type de la petición
      String contentType = "";
      String[] requestParts = request.split("\r\n");
      for (String part : requestParts) {
         if (part.contains("Content-Type")) contentType = part.split(":")[1].trim();
      }
      
      // verificar que el recurso y el Content-Type coincidan
      String extension = resource.substring(resource.lastIndexOf(".") + 1);
      if (!contentType.equals(MIME_TYPES.get(extension))) {
         System.out.println("El recurso y el Content-Type no coinciden");
         bodyRequest = "El recurso y el Content-Type no coinciden";
         response = createHead(400, "text/plain", bodyRequest.length());
         response += bodyRequest;
         return response;
      }
      
      // Si el Content-Type es Json, se verifica que el contenido sea válido
      String json = new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8);
      if (contentType.equals("application/json")) {
         if (!isValidJson(json)) {
            bodyRequest = "JSON mal formado";
            response = createHead(400, "text/plain", bodyRequest.length());
            response += bodyRequest;
            return response;
         }
      }
      
      // si el archivo no existe lo creamos
      if (!fileResource.exists()) {
         try {
            fileResource.createNewFile();
            System.out.println("Archivo creado: " + fileResource.getName());
         } catch (IOException e) {
            e.printStackTrace();
            bodyRequest = "Error al crear el archivo";
            response = createHead(500, "text/plain", bodyRequest.length());
            response += bodyRequest;
            return response;
         }
      }
      
      // Guardar el contenido del archivo
      try {
         saveFile(resource, bodyBuffer.toByteArray());
         bodyRequest = "Archivo actualizado";
         response = createHead(200, "text/plain", bodyRequest.length());
         response += bodyRequest;
      } catch (Exception e) {
         e.printStackTrace();
         bodyRequest = "Error al guardar el archivo";
         response = createHead(500, "text/plain", bodyRequest.length());
         response += bodyRequest;
         return response;
      }
      
      return response;
   }
   
   // Las peticiones HTTP DELETE se utilizan para eliminar recursos del servidor. Es una petición idempotente.
   // Ej: Eliminar un recurso, eliminar un registro de una base de datos, eliminar un archivo, etc.
   public String deleteHandler(String resource) {
      String response = "";
      String bodyResponse = "";
      
      resource = resource.substring(1); // Eliminar la barra inicial
      System.out.println("Recurso solicitado a eliminar: " + resource);
      
      // si en este punto no se entra a ningun if, se verifica si la solicitud implica eliminar un dato de algun archivo txt o json
      String[] partsOfResource = resource.split("/");
      int indexResourceFile = 0;
      String resourceFile = "";
      String resourceToDelete = "";
      int index = 1;
      for (String part : partsOfResource) {
         resourceFile += part + "/";
         if (part.contains(".")) {
            indexResourceFile = index;
            break;
         }
         index++;
      }
      resourceFile = resourceFile.substring(0, resourceFile.length() - 1); // Eliminar la última barra
      
//      System.out.println("partOfResourceFile = " + indexResourceFile);
//      System.out.println("resourceLength = " + partsOfResource.length);
      System.out.println("archivo principal = " + resourceFile);
      
      // si el archivo principal (con extension) no es el ultimo recurso, debemos eliminar un dato de un archivo txt o json
      // si el archivo principal (con extension) es el ultimo recurso, se elimina el archivo
      if (indexResourceFile < partsOfResource.length) {
         resourceToDelete = partsOfResource[indexResourceFile];
         System.out.println("archivo a eliminar = " + resourceToDelete);
         if (resourceFile.endsWith(".txt")) {
            response = deleteDataFromFile(resourceFile, resourceToDelete);
            
         } else if (resourceFile.endsWith(".json")) {
            if (deleteDataFromJsonFile(new File(resourceFile), resourceToDelete)) {
               bodyResponse = "Dato eliminado del archivo JSON";
               response = createHead(200, "text/plain", bodyResponse.length());
               response += bodyResponse;
            } else {
               bodyResponse = "Clave no encontrada en el archivo JSON";
               response = createHead(404, "text/plain", bodyResponse.length());
               response += bodyResponse;
            }
            
         } else {
            bodyResponse = "No se puede eliminar datos de un archivo de este tipo";
            response = createHead(400, "text/plain", bodyResponse.length());
            response += bodyResponse;
         }
         
         return response;

      } else {
         // Si el recurso solicitado es un directorio, se envía un mensaje de error
         File file = new File(resource);
         if (file.isDirectory()) {
            bodyResponse = "Por medidas de seguridad, no se permite eliminar directorios";
            response = createHead(400, "text/plain", bodyResponse.length());
            response += bodyResponse;
            return response;
         }
         
         // Si el archivo no existe, se envía un mensaje de error
         if (!file.exists()) {
            bodyResponse = "Archivo no encontrado";
            response = createHead(404, "text/plain", bodyResponse.length());
            response += bodyResponse;
            return response;
         }
         
         // Si el archivo existe, se elimina
         if (file.delete()) {
            bodyResponse = "Archivo eliminado";
            response = createHead(200, "text/plain", bodyResponse.length());
            response += bodyResponse;
            return response;
         } else {
            bodyResponse = "Error al eliminar el archivo";
            response = createHead(500, "text/plain", bodyResponse.length());
            response += bodyResponse;
            return response;
         }
      }
   }
   
   // Las cabeceras HTTP HEAD son similares a las cabeceras GET, pero no incluyen el cuerpo de la respuesta.
   // Se utilizan para obtener información sobre un recurso sin tener que recuperar todo el contenido.
   public String headHandler(String resource) {
      String response = "";
      
      System.out.println("Petición HEAD con recurso: " + resource);
      
      // Si la solicitud es para la raíz o index.html
      if (resource.equals("/") || resource.equals("/index.html") || resource.equals("/index.htm") || resource == null) {
         File file = new File("index.html");
         if (file.exists() && file.isFile()) {
            response = createHead(200, "text/html", file.length());
         } else {
            response = createHead(404, "text/plain", 0);
         }
         return response;
      }
      
      // Procesar otros recursos
      resource = resource.substring(1); // Eliminar la barra inicial
      System.out.println("Recurso solicitado: " + resource);
      File file = new File(resource);
      
      if (file.isDirectory()) {
         // Si el recurso es un directorio, devolver un error 403 Forbidden
         response = createHead(403, "text/plain", 0);
      } else if (file.exists() && file.isFile()) {
         // Si el archivo existe, devolver 200 con los detalles
         String mimeType = MIME_TYPES.get(resource.substring(resource.lastIndexOf(".") + 1));
         if (mimeType == null) {
            mimeType = "application/octet-stream"; // Tipo genérico si no está en el mapa
         }
         response = createHead(200, mimeType, file.length());
      } else {
         // Si el archivo no existe, devolver 404 Not Found
         response = createHead(404, "text/plain", 0);
      }
      
      return response;
   }
   
   public String deleteDataFromFile(String fileName, String dataToDelete) {
      File file = new File(fileName);
      String response = "";
      String bodyResponse = "";
      
      if (file.exists() && file.isFile()) {
         // Leer el contenido del archivo
         String fileContent = "";
         try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
               if (!line.contains(dataToDelete)) { // Si la línea no contiene el dato a eliminar, se agrega al contenido del archivo
                  fileContent += line + "\n";
               }
            }
         } catch (IOException e) {
            e.printStackTrace();
         }
         
         // Guardar el contenido actualizado en el archivo
         try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            writer.write(fileContent);
         } catch (IOException e) {
            e.printStackTrace();
         }
         
         bodyResponse = "Datos eliminados del archivo";
         response = createHead(200, "text/plain", bodyResponse.length());
         response += bodyResponse;
      } else {
         bodyResponse = "Archivo no encontrado";
         response = createHead(404, "text/plain", bodyResponse.length());
         response += bodyResponse;
      }
      
      return response;
   }
   
   public boolean deleteDataFromJsonFile(File file, String keyToDelete) {
      boolean found = false;
      
      try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
         // Leer el contenido del archivo JSON
         StringBuilder jsonContent = new StringBuilder();
         String line;
         
         while ((line = reader.readLine()) != null) {
            jsonContent.append(line);
         }
         
         // Parsear el contenido como JSON
         JsonObject jsonObject = JsonParser.parseString(jsonContent.toString()).getAsJsonObject();
         
         // Verificar si la clave existe
         if (jsonObject.has(keyToDelete)) {
            jsonObject.remove(keyToDelete); // Eliminar la clave
            found = true;
            
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            
            // Guardar el JSON actualizado en el archivo
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
               writer.write(gson.toJson(jsonObject));
            }
         }
      } catch (IOException e) {
         e.printStackTrace();
         return false; // Error durante el proceso
      }
      
      return found; // Devuelve si la clave fue encontrada y eliminada
   }
   
   // Metodo para eliminar acentos y caracteres especiales de una cadena de texto. Util para evitar problemas con el envio de respuestas HTTP
   public String deleteAcents(String text) {
      return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "");
   }
   
   // Metodo para crear una respuesta HTTP (cabecera)
   public String createHead(int statusCode, String mimeType, long fileSize) {
      return "HTTP/1.1 " + statusCode + " " + HTTP_STATUS_CODES.get(statusCode) + "\r\n"
              + "Server: Hervert Server/1.0\r\n"
              + "Date: " + new Date() + "\r\n"
              + "Content-Type: " + mimeType + "\r\n"
              + "Content-Length: " + fileSize + "\r\n"
              + "Connection: close\r\n"
              + "\r\n";
   }
   
   // Metodo para crear una respuesta HTTP (cabecera) para redireccionamiento
   public String createHeadRedirect(int statusCode, String mimeType, long fileSize, String location) {
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
   public void sendFile(String fileToSend, DataOutputStream dataOutput) {
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
         String response = createHead(200, mimeType, file.length());
         
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
   
   public int updateFileText(String fileName, String text, boolean replace) {
      // Actualizar el contenido de un archivo solo si existe y es de texto
      File file = new File(fileName);
      
      if (file.exists() && file.isFile() && fileName.endsWith(".txt")) { // Mejor usar endsWith para mayor precisión
         try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, !replace))) {
            // Si replace es true, el archivo se abre en modo de sobrescritura.
            // Si replace es false, el archivo se abre en modo de append.
            writer.write(text);
         } catch (IOException e) {
            e.printStackTrace();
            return 500; // Código de error para problemas del servidor
         }
         return 200; // Código de éxito
      } else {
         return 404; // Código de error para archivo no encontrado
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
      
      // Crear el selector
      this.selector = Selector.open();
      
      // Crear el socket del servidor y el pool de hilos
      this.serverSocketChannel = ServerSocketChannel.open();
      this.serverSocketChannel.configureBlocking(false);
      //this.serverSocketChannel.socket().bind(new InetSocketAddress(PORT));
      this.serverSocketChannel.bind(new InetSocketAddress(PORT));
      this.serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
      
      // Configurar el pool de hilos para procesar las conexiones entrantes
      this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
      
      System.out.println("Servidor web iniciado en el puerto \u001B[32m" + PORT + "\u001B[0m");
      System.out.println("\u001B[34mEsperando conexiones...\n\u001B[0m");
      
      while (true) {
         // Esperar por eventos
         selector.select();
         
         // Obtener los eventos
         Set<SelectionKey> selectedKeys = selector.selectedKeys();
         Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
         
         // Procesar los eventos
         while (keyIterator.hasNext()) {
            // Obtener el evento actual y remover la clave para evitar procesarla de nuevo
            SelectionKey key = keyIterator.next();
            keyIterator.remove();
            
            if (key.isAcceptable()) {
               acceptConnection(key);
               
            } else if (key.isReadable()) {
               readData(key);
               
            // Las peticiones de escritura no se manejan en este punto, las procesamos segun el tipo de lectura realizada (GET, POST, PUT, DELETE, HEAD)
             } else if (key.isWritable()) {
             writeData(key);
               
            } else {
               System.out.println("Evento no reconocido");
            }
            
         }
      }
   }
   
   
   private void acceptConnection(SelectionKey key) throws IOException {
      ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
      SocketChannel clientChannel = serverChannel.accept();
      
      clientChannel.configureBlocking(false);
      clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
      
      System.out.println("Nueva conexión aceptada: \u001B[33m" + clientChannel.getRemoteAddress() + "\u001B[0m");
   }
   
   private void readData(SelectionKey key) throws IOException {
      SocketChannel clientChannel = (SocketChannel) key.channel();
      ByteBuffer buffer = ByteBuffer.allocate(65536);
      ByteArrayOutputStream dataBuffer = new ByteArrayOutputStream();
      
      int bytesRead;
      while ((bytesRead = clientChannel.read(buffer)) > 0) {
         buffer.flip();
         byte[] bytes = new byte[buffer.remaining()];
         buffer.get(bytes);
         dataBuffer.write(bytes);
         buffer.clear();
      }
      
      if (bytesRead == -1) {
         System.out.println("Conexión cerrada por el cliente: \u001B[33m" + clientChannel.getRemoteAddress() + "\u001B[0m");
         clientChannel.close();
         return;
      }
      
      String request = new String(dataBuffer.toByteArray(), StandardCharsets.UTF_8);
      System.out.println("\u001B[33mPetición recibida:\n" + request + "\u001B[0m");
      
      lecturas++; // Incrementar contador de lecturas
      
      // Registrar canal para escribir respuesta
      key.interestOps(SelectionKey.OP_WRITE);
      key.attach("HTTP/1.1 202 OK\r\n\r\nHola, mundo!"); // Adjuntar respuesta
   }
   
   private void writeData(SelectionKey key) throws IOException {
      SocketChannel clientChannel = (SocketChannel) key.channel();
      String response = (String) key.attachment();
      
      if (response == null) {
         System.out.println("No hay respuesta para enviar.Cerrando conexión.");
         clientChannel.close();
         return;
      }
      
      ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
      while (buffer.hasRemaining()) {
         clientChannel.write(buffer);
      }
      
      escrituras++;
      key.interestOps(SelectionKey.OP_READ);
      System.out.println("\u001B[32mRespuesta enviada: " + response + "\u001B[0m");
      clientChannel.close();
   }
   
   public static void main(String[] args) throws IOException {
      new WebServer();
   }
}
