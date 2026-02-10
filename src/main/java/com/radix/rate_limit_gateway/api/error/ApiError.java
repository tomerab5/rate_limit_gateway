package com.radix.rate_limit_gateway.api.error;

public class ApiError {
    private final String errorCode;   //final in java is like const
    private final String message;

//Constructor
//This is how you create a new object:  new ApiError("MISSING_HEADER", "X-Tenant-Id is required");

    public ApiError(String errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }



    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }
}
