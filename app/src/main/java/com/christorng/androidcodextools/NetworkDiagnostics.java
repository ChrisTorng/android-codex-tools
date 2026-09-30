package com.christorng.androidcodextools;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.InetAddress;
import java.util.Arrays;

final class NetworkDiagnostics {
    private static final String[] HOSTS = {"auth.openai.com", "chatgpt.com", "ntfy.sh"};

    static String run(Context context) {
        StringBuilder out = new StringBuilder();
        try {
            out.append("System resolver:\n");
            for (String host : HOSTS) {
                try {
                    out.append("  ").append(host).append(" -> ")
                            .append(Arrays.toString(InetAddress.getAllByName(host))).append("\n");
                } catch (Exception e) {
                    out.append("  ").append(host).append(" -> ERROR: ")
                            .append(e.getClass().getSimpleName()).append(": ")
                            .append(e.getMessage()).append("\n");
                }
            }

            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm.getActiveNetwork();
            out.append("\nActive network: ").append(active).append("\n");

            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                LinkProperties lp = cm.getLinkProperties(n);
                out.append("\nNetwork ").append(n);
                if (n.equals(active)) out.append(" [ACTIVE]");
                out.append("\n  transports:");
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out.append(" VPN");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out.append(" WIFI");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out.append(" CELLULAR");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out.append(" ETHERNET");
                    out.append("\n  internet=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                    out.append(" validated=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                }
                if (lp != null) {
                    out.append("\n  iface=").append(lp.getInterfaceName());
                    out.append("\n  DNS=").append(lp.getDnsServers());
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        out.append("\n  privateDNS=").append(lp.isPrivateDnsActive())
                                .append(" ").append(lp.getPrivateDnsServerName());
                    }
                }
                out.append("\n");
                for (String host : HOSTS) {
                    try {
                        out.append("  ").append(host).append(" -> ")
                                .append(Arrays.toString(n.getAllByName(host))).append("\n");
                    } catch (Exception e) {
                        out.append("  ").append(host).append(" -> ERROR: ")
                                .append(e.getClass().getSimpleName()).append(": ")
                                .append(e.getMessage()).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            out.append("\nDiagnostics ERROR: ").append(e);
        }
        return out.toString();
    }
}
