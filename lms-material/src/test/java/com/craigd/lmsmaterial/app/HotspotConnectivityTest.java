package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkInfo;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HotspotConnectivityTest {
    @Test
    public void recognizesActiveHotspotWithoutDefaultNetwork() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(null);
        NetworkInterface hotspot = mock(NetworkInterface.class);
        InterfaceAddress address = mock(InterfaceAddress.class);
        when(hotspot.getName()).thenReturn("swlan0");
        when(hotspot.isUp()).thenReturn(true);
        when(address.getBroadcast()).thenReturn(InetAddress.getByName("192.168.43.255"));
        when(hotspot.getInterfaceAddresses()).thenReturn(Collections.singletonList(address));
        try (MockedStatic<NetworkInterface> interfaces = mockStatic(NetworkInterface.class)) {
            interfaces.when(NetworkInterface::getNetworkInterfaces).thenReturn(Collections.enumeration(Collections.singletonList(hotspot)));
            assertTrue(Utils.isNetworkConnected(context));
        }
    }

    @Test
    public void remainsDisconnectedWithoutNetworkOrHotspot() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(null);
        try (MockedStatic<NetworkInterface> interfaces = mockStatic(NetworkInterface.class)) {
            interfaces.when(NetworkInterface::getNetworkInterfaces).thenReturn(Collections.emptyEnumeration());
            assertFalse(Utils.isNetworkConnected(context));
        }
    }

    @Test
    public void ignoresInactiveHotspot() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(null);
        NetworkInterface hotspot = mock(NetworkInterface.class);
        when(hotspot.getName()).thenReturn("ap0");
        when(hotspot.isUp()).thenReturn(false);
        try (MockedStatic<NetworkInterface> interfaces = mockStatic(NetworkInterface.class)) {
            interfaces.when(NetworkInterface::getNetworkInterfaces).thenReturn(Collections.enumeration(Collections.singletonList(hotspot)));
            assertFalse(Utils.isNetworkConnected(context));
        }
    }

    @Test
    public void ignoresOrdinaryWifiInterface() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(null);
        NetworkInterface wifi = mock(NetworkInterface.class);
        when(wifi.getName()).thenReturn("wlan0");
        when(wifi.isUp()).thenReturn(true);
        try (MockedStatic<NetworkInterface> interfaces = mockStatic(NetworkInterface.class)) {
            interfaces.when(NetworkInterface::getNetworkInterfaces).thenReturn(Collections.enumeration(Collections.singletonList(wifi)));
            assertFalse(Utils.isNetworkConnected(context));
        }
    }

    @Test
    public void recognizesConnectedDefaultNetworkWithoutHotspot() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true));
        try (MockedStatic<NetworkInterface> interfaces = mockStatic(NetworkInterface.class)) {
            interfaces.when(NetworkInterface::getNetworkInterfaces).thenReturn(Collections.emptyEnumeration());
            assertTrue(Utils.isNetworkConnected(context));
        }
    }
}
