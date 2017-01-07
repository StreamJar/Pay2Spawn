/*
 * Copyright (c) 2017 Dries007 & DoubleDoorDevelopment
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted (subject to the limitations in the
 * disclaimer below) provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the
 *    distribution.
 *
 *  * Neither the name of Pay2Spawn nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE
 * GRANTED BY THIS LICENSE.  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT
 * HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package net.doubledoordev.pay2spawn.trackers;

import com.google.common.base.Strings;
import com.google.gson.JsonObject;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import net.doubledoordev.pay2spawn.Pay2Spawn;
import net.doubledoordev.pay2spawn.util.Donation;
import net.minecraftforge.common.config.Configuration;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.Date;

/**
 * @author Dries007
 */
public class StreamJarTracker extends Tracker
{
    public static final String NAME = "StreamJar";
    public static final StreamJarTracker INSTANCE = new StreamJarTracker();

    private boolean enabled = false;
    private String token = "";
    private double subAmount = 5.99;

    private Socket socket;

    private StreamJarTracker() {}

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void config(Configuration cfg)
    {
        cfg.addCustomCategoryComment(NAME, "StreamJar donation tracker");
        enabled = cfg.getBoolean("enabled", NAME, enabled, "");
        token = cfg.getString("token", NAME, token, "Your StreamJar access token.");
        subAmount = cfg.get(NAME, "subAmount", subAmount, "The donation amount to process new subscribers at.").getDouble(subAmount);
    }

    @Override
    public boolean isEnabled()
    {
        return enabled && !Strings.isNullOrEmpty(token);
    }

    @Override
    public void run()
    {
        IO.Options opts = new IO.Options();
        opts.transports = new String[]{"websocket"};
        try {
            socket = IO.socket("https://jar.streamjar.tv", opts);
            registerListeners();
            socket.connect();
        } catch(URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private void registerListeners()
    {
        socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args)
            {
                Pay2Spawn.getLogger().info("Connected to StreamJar. Sending authentication information...");
                JSONObject data = new JSONObject();
                try {
                    data.put("apikey", token);
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }
                socket.emit("authenticate", data);
            }
        }).on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
            @Override
            public void call(Object... args)
            {
                Pay2Spawn.getLogger().warn("Unable to connect to StreamJar.");
                ((Exception) args[0]).printStackTrace();
            }
        }).on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args)
            {
                Pay2Spawn.getLogger().info("Disconnected from StreamJar.");
            }
        }).on("authenticated", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                handleAuthenticated();
            }
        });
    }

    private void handleAuthenticated()
    {
        Pay2Spawn.getLogger().info("Authenticated successfully with StreamJar.");
        socket.emit("channel", new JSONObject(), new Ack() {
            @Override
            public void call(Object... args) {
                JSONObject arg = (JSONObject) args[1];
                if (arg == null) {
                    Pay2Spawn.getLogger().warn("This StreamJar channel does not exist.");
                    return;
                }

                int channelId = 0;
                try {
                    channelId = arg.getInt("id");
                    socket.on(subscribeEvent(channelId, "tip"), new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            process(getDonation((JSONObject) args[0]));
                        }
                    });
                    socket.on(subscribeEvent(channelId, "sub"), new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            process(getSub((JSONObject) args[0]));
                        }
                    });
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }
            }
        });
    }

    private String subscribeEvent(int channelId, final String event) throws JSONException {
        String listener = "channel:" + channelId + ":" + event;
        JSONObject data = new JSONObject();
        data.put("event", listener);
        socket.emit("subscribe", data, new Ack() {
            @Override
            public void call(Object... args) {
                if (args[0] != null) {
                    Pay2Spawn.getLogger().warn("An unexpected error occurred when listening for " + event + ": " + args[0]);
                }
            }
        });
        socket.off(listener);
        return listener;
    }

    private Donation getDonation(JSONObject object)
    {
        try
        {
            String username = object.getString("name");
            double amount = object.getDouble("amount");
            long timestamp = new Date().getTime();
            String note = object.getString("message");
            return new Donation(username, amount, timestamp, note);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }

    private Donation getSub(JSONObject object)
    {
        try
        {
            String username = object.getString("name");
            double amount = subAmount;
            long timestamp = new Date().getTime();
            String note = "New subscriber";
            return new Donation(username, amount, timestamp, note);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }
}
