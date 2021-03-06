package org.itxtech.daedalus.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.support.v4.app.NotificationCompat;
import android.support.v4.util.Pair;
import android.system.OsConstants;
import android.util.Log;

import org.itxtech.daedalus.Daedalus;
import org.itxtech.daedalus.R;
import org.itxtech.daedalus.activity.MainActivity;
import org.itxtech.daedalus.fragment.DNSQuery;
import org.itxtech.daedalus.provider.Provider;
import org.itxtech.daedalus.provider.TcpProvider;
import org.itxtech.daedalus.provider.UdpProvider;
import org.itxtech.daedalus.receiver.StatusBarBroadcastReceiver;
import org.itxtech.daedalus.util.Logger;
import org.itxtech.daedalus.util.server.AbstractDNSServer;
import org.itxtech.daedalus.util.server.DNSServerHelper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import de.measite.minidns.DNSMessage;
import de.measite.minidns.Question;
import de.measite.minidns.Record;

/**
 * Daedalus Project
 *
 * @author iTX Technologies
 * @link https://itxtech.org
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
public class DaedalusVpnService extends VpnService implements Runnable {
    public static final String ACTION_ACTIVATE = "org.itxtech.daedalus.service.DaedalusVpnService.ACTION_ACTIVATE";
    public static final String ACTION_DEACTIVATE = "org.itxtech.daedalus.service.DaedalusVpnService.ACTION_DEACTIVATE";

    private static final int NOTIFICATION_ACTIVATED = 0;

    private static final String TAG = "DaedalusVpnService";

    public static AbstractDNSServer primaryServer;
    public static AbstractDNSServer secondaryServer;

    private List<Pair<String, Integer>> resolvedDNS;

    private NotificationCompat.Builder notification = null;

    private boolean running = false;
    private long lastUpdate = 0;
    private boolean statisticQuery;
    private Provider provider;
    private ParcelFileDescriptor descriptor;

    private Thread mThread = null;

    public HashMap<String, Pair<String, Integer>> dnsServers;

    private static boolean activated = false;

    public static boolean isActivated() {
        return activated;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            switch (intent.getAction()) {
                case ACTION_ACTIVATE:
                    activated = true;

                    if (Daedalus.getPrefs().getBoolean("settings_notification", true)) {

                        NotificationManager manager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);

                        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, createNotificationChannel(false));

                        Intent nIntent = new Intent(this, MainActivity.class);
                        PendingIntent pIntent = PendingIntent.getActivity(this, 0, nIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                        builder.setWhen(0)
                                .setContentTitle(getResources().getString(R.string.notice_activated))
                                .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
                                .setSmallIcon(R.drawable.ic_notification)
                                .setColor(getResources().getColor(R.color.colorPrimary)) //backward compatibility
                                .setAutoCancel(false)
                                .setOngoing(true)
                                .setTicker(getResources().getString(R.string.notice_activated))
                                .setContentIntent(pIntent)
                                .addAction(R.drawable.ic_clear, getResources().getString(R.string.button_text_deactivate),
                                        PendingIntent.getBroadcast(this, 0,
                                                new Intent(StatusBarBroadcastReceiver.STATUS_BAR_BTN_DEACTIVATE_CLICK_ACTION), 0))
                                .addAction(R.drawable.ic_settings, getResources().getString(R.string.action_settings),
                                        PendingIntent.getBroadcast(this, 0,
                                                new Intent(StatusBarBroadcastReceiver.STATUS_BAR_BTN_SETTINGS_CLICK_ACTION), 0));

                        Notification notification = builder.build();

                        manager.notify(NOTIFICATION_ACTIVATED, notification);

                        this.notification = builder;
                    }

                    if (this.mThread == null) {
                        this.mThread = new Thread(this, "DaedalusVpn");
                        this.running = true;
                        this.mThread.start();
                    }
                    Daedalus.updateShortcut(getApplicationContext());
                    if (MainActivity.getInstance() != null) {
                        MainActivity.getInstance().startActivity(new Intent(getApplicationContext(), MainActivity.class)
                                .putExtra(MainActivity.LAUNCH_ACTION, MainActivity.LAUNCH_ACTION_SERVICE_DONE));
                    }
                    return START_STICKY;
                case ACTION_DEACTIVATE:
                    stopThread();
                    return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    private List<Pair<String, Integer>> getResolvedDNS(AbstractDNSServer dnsServer) {
        List<Pair<String, Integer>> resolvedDNSServers = new ArrayList<>();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(dnsServer.getAddress());
            for (InetAddress address : addresses) {
                if (checkDNSServer(address, dnsServer.getPort()))
                    resolvedDNSServers.add(new Pair<>(address.getHostAddress(), dnsServer.getPort()));
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        return resolvedDNSServers;
    }

    private boolean checkDNSServer(InetAddress address, int port) {

        DNSMessage.Builder message = DNSMessage.builder()
                .addQuestion(new Question(Daedalus.DEFAULT_TEST_DOMAINS[0], Record.TYPE.A))
                .setId((new Random()).nextInt())
                .setRecursionDesired(true)
                .setOpcode(DNSMessage.OPCODE.QUERY)
                .setResponseCode(DNSMessage.RESPONSE_CODE.NO_ERROR)
                .setQrFlag(false);
        try {
            DNSMessage response = new DNSQuery().query(message.build(), address, port);
            return response.answerSection.size() > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        stopThread();
    }

    private void stopThread() {
        Log.d(TAG, "stopThread");
        activated = false;
        boolean shouldRefresh = false;
        try {
            if (this.descriptor != null) {
                this.descriptor.close();
                this.descriptor = null;
            }
            if (mThread != null) {
                running = false;
                shouldRefresh = true;
                if (provider != null) {
                    provider.shutdown();
                    mThread.interrupt();
                    provider.stop();
                } else {
                    mThread.interrupt();
                }
                mThread = null;
            }
            if (notification != null) {
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.cancel(NOTIFICATION_ACTIVATED);
                notification = null;
            }
            dnsServers = null;
        } catch (Exception e) {
            Logger.logException(e);
        }
        stopSelf();

        if (shouldRefresh) {
            Logger.info("shecan service has stopped");
        }

        if (shouldRefresh && MainActivity.getInstance() != null) {
            MainActivity.getInstance().startActivity(new Intent(getApplicationContext(), MainActivity.class)
                    .putExtra(MainActivity.LAUNCH_ACTION, MainActivity.LAUNCH_ACTION_SERVICE_DONE));
        } else if (shouldRefresh) {
            Daedalus.updateShortcut(getApplicationContext());
        }
    }

    @Override
    public void onRevoke() {
        stopThread();
    }

    private InetAddress addDnsServer(Builder builder, String format, byte[] ipv6Template, Pair<String, Integer> destination) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(destination.first);
        int size = dnsServers.size();
        size++;
        if (address instanceof Inet6Address && ipv6Template == null) {
            Log.i(TAG, "addDnsServer: Ignoring DNS server " + address);
        } else if (address instanceof Inet4Address) {
            String alias = String.format(Locale.US, format, size + 1);
            dnsServers.put(alias, destination);
            builder.addRoute(alias, 32);
            return InetAddress.getByName(alias);
        } else if (address instanceof Inet6Address) {
            ipv6Template[ipv6Template.length - 1] = (byte) (size + 1);
            InetAddress i6addr = Inet6Address.getByAddress(ipv6Template);
            dnsServers.put(i6addr.getHostAddress(), destination);
            return i6addr;
        }
        return null;
    }

    @Override
    public void run() {
        try {
            resolvedDNS = getResolvedDNS(primaryServer);
            resolvedDNS.addAll(getResolvedDNS(secondaryServer));

            if (resolvedDNS.size() == 0) {
                Log.d(TAG, "No DNS server is reachable.");
                stopThread();
                return;
            }
//            DNSServerHelper.buildPortCache(resolvedDNS);

            Builder builder = new Builder()
                    .setSession("shecan")
                    .setConfigureIntent(PendingIntent.getActivity(this, 0,
                            new Intent(this, MainActivity.class).putExtra(MainActivity.LAUNCH_FRAGMENT, MainActivity.FRAGMENT_SETTINGS),
                            PendingIntent.FLAG_ONE_SHOT));
            String format = null;
            for (String prefix : new String[]{"10.0.0", "192.0.2", "198.51.100", "203.0.113", "192.168.50"}) {
                try {
                    builder.addAddress(prefix + ".1", 24);
                } catch (IllegalArgumentException e) {
                    continue;
                }

                format = prefix + ".%d";
                break;
            }

            boolean advanced = true; //Daedalus.getPrefs().getBoolean("settings_advanced_switch", false);

            statisticQuery = Daedalus.getPrefs().getBoolean("settings_count_query_times", false);
            byte[] ipv6Template = new byte[]{32, 1, 13, (byte) (184 & 0xFF), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

            boolean hasIPv6 = false;

            for (Pair<String, Integer> pair : resolvedDNS) {
                if (pair.first.contains(":"))
                    hasIPv6 = true;

//                if (!advanced && pair.second != AbstractDNSServer.DNS_SERVER_DEFAULT_PORT)
//                    advanced = true;
            }

            if (hasIPv6) {//IPv6
                try {
                    InetAddress addr = Inet6Address.getByAddress(ipv6Template);
                    Log.d(TAG, "configure: Adding IPv6 address" + addr);
                    builder.addAddress(addr, 120);
                } catch (Exception e) {
                    Logger.logException(e);

                    ipv6Template = null;
                }
            } else {
                ipv6Template = null;
            }

            InetAddress alias;

            if (advanced)
                dnsServers = new HashMap<>();

            for (Pair<String, Integer> pair : resolvedDNS) {
                if (advanced) {
                    alias = addDnsServer(builder, format, ipv6Template, pair);
                } else {
                    alias = InetAddress.getByName(pair.first);
                }

                Logger.info("shecan is listening on " + pair.first + ":" + pair.second + " as " + alias);
                builder.addDnsServer(alias);
            }



            if (advanced) {
                builder.setBlocking(true);
                builder.allowFamily(OsConstants.AF_INET);
                builder.allowFamily(OsConstants.AF_INET6);
            }

            descriptor = builder.establish();
            Logger.info("shecan service is started");

            if (advanced) {
                if (Daedalus.getPrefs().getBoolean("settings_dns_over_tcp", false)) {
                    provider = new TcpProvider(descriptor, this);
                } else {
                    provider = new UdpProvider(descriptor, this);
                }
                provider.start();
                provider.process();
            } else {
                while (running) {
                    Thread.sleep(1000);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            Logger.logException(e);
        } finally {
            Log.d(TAG, "quit");
            stopThread();
        }
    }

    public void providerLoopCallback() {
        if (statisticQuery) {
            updateUserInterface();
        }
    }

    private void updateUserInterface() {
        long time = System.currentTimeMillis();
        if (time - lastUpdate >= 1000) {
            lastUpdate = time;
            if (notification != null) {
                notification.setContentTitle(getResources().getString(R.string.notice_queries) + " " + String.valueOf(provider.getDnsQueryTimes()));
                NotificationManager manager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
                manager.notify(NOTIFICATION_ACTIVATED, notification.build());
            }
        }
    }


    public static class VpnNetworkException extends Exception {
        public VpnNetworkException(String s) {
            super(s);
        }

        public VpnNetworkException(String s, Throwable t) {
            super(s, t);
        }

    }

    public String createNotificationChannel(boolean allowHiding){
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            if(allowHiding && Daedalus.getPrefs().getBoolean("hide_notification_icon", false)){
                NotificationChannel channel = new NotificationChannel("noIconChannel", getString(R.string.notification_channel_hiddenicon), NotificationManager.IMPORTANCE_MIN);
                channel.enableLights(false);
                channel.enableVibration(false);
                channel.setDescription(getString(R.string.notification_channel_hiddenicon_description));
                notificationManager.createNotificationChannel(channel);
                return "noIconChannel";
            }else{
                NotificationChannel channel = new NotificationChannel("defaultchannel", getString(R.string.notification_channel_default), NotificationManager.IMPORTANCE_LOW);
                channel.enableLights(false);
                channel.enableVibration(false);
                channel.setDescription(getString(R.string.notification_channel_default_description));
                notificationManager.createNotificationChannel(channel);
                return "defaultchannel";
            }
        }else{
            return "defaultchannel";
        }
    }


}
