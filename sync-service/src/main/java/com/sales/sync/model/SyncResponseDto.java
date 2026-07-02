package com.sales.sync.model;

import java.util.List;

public class SyncResponseDto {
    private boolean success;
    private List<String> processedMutationIds;
    private String errorMessage;

    public SyncResponseDto() {}

    public SyncResponseDto(boolean success, List<String> processedMutationIds, String errorMessage) {
        this.success = success;
        this.processedMutationIds = processedMutationIds;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public List<String> getProcessedMutationIds() {
        return processedMutationIds;
    }

    public void setProcessedMutationIds(List<String> processedMutationIds) {
        this.processedMutationIds = processedMutationIds;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
