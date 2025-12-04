package org.steveww.spark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetryUtil {
    private static final Logger logger = LoggerFactory.getLogger(RetryUtil.class);
    
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }

    public static <T> T withRetry(RetryableOperation<T> operation, int maxAttempts, long delayMs, String operationName) {
        int attempt = 1;
        while (true) {
            try {
                return operation.execute();
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    logger.error("Failed {} after {} attempts", operationName, maxAttempts, e);
                    throw new RuntimeException("Operation failed after " + maxAttempts + " attempts", e);
                }
                logger.warn("Attempt {} of {} for {} failed, retrying in {}ms", attempt, maxAttempts, operationName, delayMs, e);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
                attempt++;
            }
        }
    }
}