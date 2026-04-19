package com.children.doctor;

import com.children.doctor.plugins.dualcamera.DualCameraPlugin;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(DualCameraPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
