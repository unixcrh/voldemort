package voldemort.server.rest;

import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import voldemort.VoldemortUnsupportedOperationalException;
import voldemort.store.InvalidMetadataException;
import voldemort.store.PersistenceFailureException;
import voldemort.store.rebalancing.ProxyUnreachableException;
import voldemort.versioning.ObsoleteVersionException;

public class RestServerPutErrorHandler extends RestServerErrorHandler {

    /**
     * Handle exceptions thrown by the storage. Exceptions specific to PUT go
     * here. Pass other exceptions to the parent class
     * 
     * TODO REST-Server Add a new exception for this condition - server busy
     * with pending requests. queue is full
     */
    @Override
    public void handleExceptions(MessageEvent messageEvent, Exception exception) {

        if(exception instanceof InvalidMetadataException) {
            writeErrorResponse(messageEvent,
                               HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                               "The requested key does not exist in this partition");
        } else if(exception instanceof PersistenceFailureException) {
            writeErrorResponse(messageEvent,
                               HttpResponseStatus.INTERNAL_SERVER_ERROR,
                               "TOperation failed");
        } else if(exception instanceof ProxyUnreachableException) {
            writeErrorResponse(messageEvent,
                               HttpResponseStatus.SERVICE_UNAVAILABLE,
                               "The proxy is unreachable");
        } else if(exception instanceof VoldemortUnsupportedOperationalException) {
            writeErrorResponse(messageEvent,
                               HttpResponseStatus.METHOD_NOT_ALLOWED,
                               "operation not supported in read-only store");
        } else if(exception instanceof ObsoleteVersionException) {
            writeErrorResponse(messageEvent,
                               HttpResponseStatus.PRECONDITION_FAILED,
                               "A put request resulted in an ObsoleteVersionException");
        } else {
            super.handleExceptions(messageEvent, exception);
        }
    }
}