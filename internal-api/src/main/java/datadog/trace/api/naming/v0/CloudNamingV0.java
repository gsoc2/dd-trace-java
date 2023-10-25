package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CloudNamingV0 implements NamingSchema.ForCloud {
  private final boolean allowInferredServices;

  public CloudNamingV0(boolean allowInferredServices) {
    this.allowInferredServices = allowInferredServices;
  }

  @Nonnull
  @Override
  public String operationForRequest(
      @Nonnull final String provider,
      @Nonnull final String cloudService,
      @Nonnull final String qualifiedOperation) {
    // only aws sdk is right now implemented
    return "aws.http";
  }

  @Override
  public String serviceForRequest(
      @Nonnull final String provider, @Nullable final String cloudService) {
    if (!allowInferredServices) {
      return null;
    }

    // we only manage aws. Future switch for other cloud providers will be needed in the future
    if (cloudService == null) {
      return "java-aws-sdk";
    }

    switch (cloudService) {
      case "sns":
      case "sqs":
        return cloudService;
      default:
        return "java-aws-sdk";
    }
  }

  @Nonnull
  @Override
  public String operationForFaas(@Nonnull final String provider) {
    return "dd-tracer-serverless-span";
  }
}
