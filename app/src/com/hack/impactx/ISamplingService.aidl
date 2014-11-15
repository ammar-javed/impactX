package com.hack.impactx;

interface ISamplingService {
  void setCallback( in IBinder binder );
  void removeCallback();
  void stopSampling();
  boolean isSampling();
  void setSensitivity( in double sensitivity );
  void setNoiseSensitivity( in int noiseSensitivity );
}
