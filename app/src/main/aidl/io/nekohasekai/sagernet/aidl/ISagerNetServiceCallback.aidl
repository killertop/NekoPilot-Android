package io.nekohasekai.sagernet.aidl;


oneway interface ISagerNetServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void missingPlugin(String profileName, String pluginName);
}
