package com.children.doctor;

import com.children.doctor.plugins.dualcamera.DualCameraPlugin;
import com.children.doctor.plugins.hitiprinter.HiTiPrinterPlugin;
import com.children.doctor.plugins.hitiprinter.HiTiPrinterPlugin_Current;
import com.children.doctor.plugins.quitapp.QuitAppPlugin;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(DualCameraPlugin.class);
        registerPlugin(HiTiPrinterPlugin.class);
        registerPlugin(HiTiPrinterPlugin_Current.class);
        registerPlugin(QuitAppPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
