package io.nekohasekai.sagernet.aidl;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();
  Bundle getLocalProxyEndpoint();
  Bundle getTrafficSnapshot();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
  boolean protectSocket(in ParcelFileDescriptor socket);
  boolean selectProfile(long profileId);
  boolean setAutomaticNodeSwitchingEnabled(boolean enabled);
}
