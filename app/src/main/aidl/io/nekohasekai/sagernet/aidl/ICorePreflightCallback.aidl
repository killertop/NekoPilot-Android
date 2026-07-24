package io.nekohasekai.sagernet.aidl;

/** Internal, one-shot result for an isolated candidate-core health check. */
oneway interface ICorePreflightCallback {
  void complete(boolean healthy, String failure);
}
