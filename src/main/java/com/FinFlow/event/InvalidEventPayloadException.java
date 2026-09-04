package com.FinFlow.event;

public class InvalidEventPayloadException extends RuntimeException {
  public InvalidEventPayloadException(String message) {
    super(message);
  }

  public InvalidEventPayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
