package io.nekohasekai.sagernet.aidl;

import android.os.Bundle;
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();
  Bundle getLocalProxyEndpoint();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
  boolean selectProfile(long profileId);
  boolean setAutomaticNodeSelectionEnabled(boolean enabled);
}
