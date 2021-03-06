package ee.eesti.riha.rest.service.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ee.eesti.riha.rest.error.RihaRestException;
import ee.eesti.riha.rest.logic.ChangeLogic;
import ee.eesti.riha.rest.logic.FileResourceLogic;
import ee.eesti.riha.rest.logic.MyExceptionHandler;
import ee.eesti.riha.rest.logic.Validator;
import ee.eesti.riha.rest.logic.util.FileHelper;
import ee.eesti.riha.rest.logic.util.JsonHelper;
import ee.eesti.riha.rest.model.Document;
import ee.eesti.riha.rest.model.FileResource;
import ee.eesti.riha.rest.service.FileService;
import ee.eesti.riha.rest.util.PagedRequest;
import ee.eesti.riha.rest.util.PagedRequestArgumentResolver;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import javax.activation.DataHandler;
import javax.ws.rs.core.*;
import javax.ws.rs.core.Response.Status;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.UUID;

// TODO: Auto-generated Javadoc

/**
 * The Class FileServiceImpl.
 */
public class FileServiceImpl implements FileService {

    private static final Logger LOG = LoggerFactory.getLogger(FileServiceImpl.class);

    @Autowired
    ChangeLogic changeLogic;

    @Autowired
    private FileResourceLogic fileResourceLogic;

    @Autowired
    private PagedRequestArgumentResolver pagedRequestArgumentResolver;

    /*
     * (non-Javadoc)
     *
     * @see ee.eesti.riha.rest.service.FileService#getDocument(java.lang.Integer, java.lang.String)
     */
    @Override
    public Response getDocument(Integer documentId, String token) {

        try {
            String fields = "[\"document_id\", \"filename\"]";
            ObjectNode jsonObject = (ObjectNode) changeLogic.doGet(Document.class, documentId, fields);

            return getDocumentLogic(documentId, jsonObject);

        } catch (RihaRestException e) {
            return Response.status(Status.BAD_REQUEST).entity(MyExceptionHandler.unmapped(e, "FileService error"))
                    .type(MediaType.APPLICATION_JSON + "; charset=UTF-8").build();
        }

    }

    private Response getDocumentLogic(Integer documentId, ObjectNode document) throws RihaRestException {
        String documentFilePath = FileHelper.createDocumentFilePathWithRoot(documentId);
        File file = new File(documentFilePath);

        Validator.documentFileMustExist(file, documentId);

        String fileName = JsonHelper.get(document, "filename", file.getName());

        return Response.ok(file).header("content-disposition", "attachment; filename =" + fileName).build();
    }

    @Override
    public Response upload(Attachment attachment, String infoSystemUuidStr) {
        DataHandler dataHandler = attachment.getDataHandler();
        String name = dataHandler.getName();
        String contentType = dataHandler.getContentType();
        UUID infoSystemUuid = StringUtils.hasText(infoSystemUuidStr)
                ? UUID.fromString(infoSystemUuidStr)
                : null;

        if (LOG.isInfoEnabled()) {
            LOG.info("Receiving upload of file '{}' with content type '{}'", name, contentType);
        }

        try {
            UUID fileResourceUuid = fileResourceLogic.create(dataHandler.getInputStream(), infoSystemUuid, name, contentType);
            return Response.ok(fileResourceUuid.toString()).build();
        } catch (IOException e) {
            throw new IllegalStateException("Could not retrieve request attachment input stream", e);
        }
    }

    @Override
    public Response download(String fileUuidStr, String infoSystemUuidStr) {
        final UUID fileResourceUuid = UUID.fromString(fileUuidStr);
        UUID infoSystemUuid = StringUtils.hasText(infoSystemUuidStr)
                ? UUID.fromString(infoSystemUuidStr)
                : null;
        LOG.debug("Handling file {} download", fileResourceUuid);

        FileResource fileResource = fileResourceLogic.get(fileResourceUuid, infoSystemUuid);
        if (fileResource == null) {
            return Response.status(Status.NOT_FOUND).build();
        }

        StreamingOutput streamingOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output) throws IOException {
                try {
                    fileResourceLogic.copyLargeObjectData(fileResourceUuid, output);
                } catch (SQLException e) {
                    throw new IllegalStateException("Could not retrieve requested file data", e);
                }
            }
        };

        Response.ResponseBuilder response = Response.ok();

        if (fileResource.getLargeObject().getLength() != null) {
            response.header(HttpHeaders.CONTENT_LENGTH, fileResource.getLargeObject().getLength());
        }

        if (StringUtils.hasText(fileResource.getContentType())) {
            response.header(HttpHeaders.CONTENT_TYPE, fileResource.getContentType());
        }

        if (StringUtils.hasText(fileResource.getName())) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, "filename=\"" + fileResource.getName() + "\"");
        }

        return response.entity(streamingOutput).build();
    }

    @Override
    public Response list(UriInfo uriInfo) {
        PagedRequest request = pagedRequestArgumentResolver.resolve(uriInfo.getQueryParameters());
        return Response.ok(fileResourceLogic.list(request)).build();
    }

}
