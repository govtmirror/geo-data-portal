package gov.usgs.service;

import gov.usgs.cida.gdp.utilities.FileHelper;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.mail.MessagingException;
import javax.servlet.ServletException;
import javax.servlet.http.*;
import org.apache.commons.codec.binary.Base64OutputStream;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

public class UploadHandlerServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String SUFFIX_SHP = ".shp";
    private static final String SUFFIX_SHX = ".shx";
    private static final String SUFFIX_PRJ = ".prj";
    private static final String SUFFIX_DBF = ".dbf";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, FileNotFoundException {
        doPost(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, FileNotFoundException {

        int maxFileSize = Integer.parseInt(request.getParameter("maxfilesize"));
        int fileSize = Integer.parseInt(request.getHeader("Content-Length"));
        if (fileSize > maxFileSize) {
            sendErrorResponse(response, "Upload exceeds max file size of " + maxFileSize + " bytes");
            return;
        }

        // qqfile is parameter passed by our javascript uploader
        String filename = request.getParameter("qqfile");
        String utilityWpsUrl = request.getParameter("utilitywps");
        String wfsEndpoint = request.getParameter("wfs-url");
        String tempDir = System.getProperty("java.io.tmpdir");

        File destinationFile = new File(tempDir + File.separator + filename);

        // Handle form-based upload (from IE)
        if (ServletFileUpload.isMultipartContent(request)) {
            FileItemFactory factory = new DiskFileItemFactory();
            ServletFileUpload upload = new ServletFileUpload(factory);

            // Parse the request
            FileItemIterator iter;
            try {
                iter = upload.getItemIterator(request);
                while (iter.hasNext()) {
                    FileItemStream item = iter.next();
                    String name = item.getFieldName();
                    if ("qqfile".equals(name)) {
                        saveFileFromRequest(item.openStream(), destinationFile);
                        break;
                    }
                }
            } catch (Exception ex) {
                sendErrorResponse(response, "Unable to upload file");
                return;
            }
        } else {
            // Handle octet streams (from standards browsers)
            try {
                saveFileFromRequest(request.getInputStream(), destinationFile);
            } catch (IOException ex) {
                Logger.getLogger(UploadHandlerServlet.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        try {
            if (!FileHelper.validateShapeZIP(destinationFile)) {
                throw new IOException();
            }
        } catch (IOException ex) {
            sendErrorResponse(response, "Unable to verify shapefile. Upload failed");
            return;
        }

        String responseText = null;
        try {
            String wpsResponse = postToWPS(utilityWpsUrl, wfsEndpoint, destinationFile);

            responseText = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<wpsResponse><![CDATA[" + wpsResponse + "]]></wpsResponse>";

        } catch (Exception ex) {
            Logger.getLogger(UploadHandlerServlet.class.getName()).log(Level.SEVERE, null, ex);
            sendErrorResponse(response, "Unable to upload file");
            return;
        } finally {
            FileUtils.deleteQuietly(destinationFile);
        }

        sendResponse(response, responseText);
    }

    public static void sendErrorResponse(HttpServletResponse response, String text) {
        sendResponse(response, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<error>" + text + "</error>");
    }

    public static void sendResponse(HttpServletResponse response, String text) {

        response.setContentType("text/xml");
        response.setCharacterEncoding("utf-8");

        try {
            Writer writer = response.getWriter();
            writer.write(text);
            writer.close();
        } catch (IOException ex) {
            Logger.getLogger(UploadHandlerServlet.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void saveFileFromRequest(InputStream is, File destinationFile) throws IOException {
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(destinationFile);
            IOUtils.copy(is, os);
        } finally {
            IOUtils.closeQuietly(os);
        }
    }

    private String postToWPS(String url, String wfsEndpoint, File uploadedFile) throws IOException, MessagingException {
        HttpPost post;
        HttpClient httpClient = new DefaultHttpClient();

        post = new HttpPost(url);

        File wpsRequestFile = createWPSReceiveFilesXML(uploadedFile, wfsEndpoint);
        FileInputStream wpsRequestInputStream = null;
        try {
            wpsRequestInputStream = new FileInputStream(wpsRequestFile);

            AbstractHttpEntity entity = new InputStreamEntity(wpsRequestInputStream, wpsRequestFile.length());

            post.setEntity(entity);

            HttpResponse response = httpClient.execute(post);

            return EntityUtils.toString(response.getEntity());

        } finally {
            IOUtils.closeQuietly(wpsRequestInputStream);
            FileUtils.deleteQuietly(wpsRequestFile);
        }
    }

    private static File createWPSReceiveFilesXML(final File uploadedFile, final String wfsEndpoint) throws IOException, MessagingException {

        File wpsRequestFile = null;
        FileOutputStream wpsRequestOutputStream = null;
        FileInputStream uploadedInputStream = null;

        try {
            wpsRequestFile = File.createTempFile("wps.upload.", ".xml");
            wpsRequestOutputStream = new FileOutputStream(wpsRequestFile);
            uploadedInputStream = new FileInputStream(uploadedFile);

            wpsRequestOutputStream.write(new String(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<wps:Execute service=\"WPS\" version=\"1.0.0\" "
                    + "xmlns:wps=\"http://www.opengis.net/wps/1.0.0\" "
                    + "xmlns:ows=\"http://www.opengis.net/ows/1.1\" "
                    + "xmlns:xlink=\"http://www.w3.org/1999/xlink\" "
                    + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                    + "xsi:schemaLocation=\"http://www.opengis.net/wps/1.0.0 "
                    + "http://schemas.opengis.net/wps/1.0.0/wpsExecute_request.xsd\">"
                    + "<ows:Identifier>gov.usgs.cida.gdp.wps.algorithm.filemanagement.ReceiveFiles</ows:Identifier>"
                    + "<wps:DataInputs>"
                    + "<wps:Input>"
                    + "<ows:Identifier>filename</ows:Identifier>"
                    + "<wps:Data>"
                    + "<wps:LiteralData>"
                    + StringEscapeUtils.escapeXml(uploadedFile.getName().replace(".zip", ""))
                    + "</wps:LiteralData>"
                    + "</wps:Data>"
                    + "</wps:Input>"
                    + "<wps:Input>"
                    + "<ows:Identifier>wfs-url</ows:Identifier>"
                    + "<wps:Data>"
                    + "<wps:LiteralData>"
                    + StringEscapeUtils.escapeXml(wfsEndpoint)
                    + "</wps:LiteralData>"
                    + "</wps:Data>"
                    + "</wps:Input>"
                    + "<wps:Input>"
                    + "<ows:Identifier>file</ows:Identifier>"
                    + "<wps:Data>"
                    + "<wps:ComplexData mimeType=\"application/x-zipped-shp\" encoding=\"Base64\">").getBytes());
            IOUtils.copy(uploadedInputStream, new Base64OutputStream(wpsRequestOutputStream, true, 0, null));
            wpsRequestOutputStream.write(new String(
                    "</wps:ComplexData>"
                    + "</wps:Data>"
                    + "</wps:Input>"
                    + "</wps:DataInputs>"
                    + "<wps:ResponseForm>"
                    + "<wps:ResponseDocument>"
                    + "<wps:Output>"
                    + "<ows:Identifier>result</ows:Identifier>"
                    + "</wps:Output>"
                    + "<wps:Output>"
                    + "<ows:Identifier>wfs-url</ows:Identifier>"
                    + "</wps:Output>"
                    + "<wps:Output>"
                    + "<ows:Identifier>featuretype</ows:Identifier>"
                    + "</wps:Output>"
                    + "</wps:ResponseDocument>"
                    + "</wps:ResponseForm>"
                    + "</wps:Execute>").getBytes());
        } finally {
            IOUtils.closeQuietly(wpsRequestOutputStream);
            IOUtils.closeQuietly(uploadedInputStream);
        }
        return wpsRequestFile;
    }
}
