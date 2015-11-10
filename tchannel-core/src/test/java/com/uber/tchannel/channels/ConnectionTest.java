/*
 * Copyright (c) 2015 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.tchannel.channels;

import com.google.common.util.concurrent.ListenableFuture;
import com.uber.tchannel.api.SubChannel;
import com.uber.tchannel.api.TChannel;
import com.uber.tchannel.api.handlers.RequestHandler;
import com.uber.tchannel.errors.ErrorType;
import com.uber.tchannel.messages.RawRequest;
import com.uber.tchannel.messages.RawResponse;
import com.uber.tchannel.messages.Request;
import com.uber.tchannel.messages.Response;
import io.netty.util.CharsetUtil;
import org.junit.Test;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

import static java.lang.Thread.sleep;
import static org.junit.Assert.assertTrue;

public class ConnectionTest {

    @Test
    public void testConnectionReset() throws Exception {

        InetAddress host = InetAddress.getByName("127.0.0.1");

        // create server
        final TChannel server = new TChannel.Builder("server")
            .setServerHost(host)
            .build();
        final SubChannel subServer = server.makeSubChannel("server")
            .register("echo", new EchoHandler());
        server.listen();

        int port = server.getListeningPort();

        // create client
        final TChannel client = new TChannel.Builder("client")
                .setServerHost(host)
                .build();
        final SubChannel subClient = client.makeSubChannel("server");
        client.listen();

        RawRequest req = new RawRequest.Builder("server", "echo")
            .setHeader("title")
            .setBody("hello")
            .setTimeout(2000)
            .build();

        ListenableFuture<RawResponse> future = subClient.send(
            req,
            host,
            port
        );

        Response res = (Response)future.get();
        assertEquals(res.getArg2().toString(CharsetUtil.UTF_8), "title");
        assertEquals(res.getArg3().toString(CharsetUtil.UTF_8), "hello");
        res.release();

        // checking the connections
        Map<String, Integer> stats = client.getPeerManager().getStats();
        assertEquals((int)stats.get("connections.in"), 0);
        assertEquals((int)stats.get("connections.out"), 1);

        stats = server.getPeerManager().getStats();
        assertEquals((int)stats.get("connections.in"), 1);
        assertEquals((int)stats.get("connections.out"), 0);

        client.shutdown();
        sleep(100);

        stats = client.getPeerManager().getStats();
        assertEquals((int)stats.get("connections.in"), 0);
        assertEquals((int)stats.get("connections.out"), 0);

        stats = server.getPeerManager().getStats();
        assertEquals((int)stats.get("connections.in"), 0);
        assertEquals((int)stats.get("connections.out"), 0);

        server.shutdown();
    }

    @Test
    public void testConnectionFailure() throws Exception {

        InetAddress host = InetAddress.getByName("127.0.0.1");

        // create client
        final TChannel client = new TChannel.Builder("client")
            .setServerHost(host)
            .build();
        final SubChannel subClient = client.makeSubChannel("server");

        RawRequest req = new RawRequest.Builder("server", "echo")
            .setHeader("title")
            .setBody("hello")
            .setTimeout(2000)
            .build();

        ListenableFuture<RawResponse> future = subClient.send(
            req,
            host,
            8888
        );

        Response res = (Response)future.get();
        assertTrue(res.isError());
        assertEquals(ErrorType.NetworkError, res.getError().getErrorType());
        assertEquals("Failed to connect to the host", res.getError().getMessage());

        res.release();
        client.shutdown();
    }

    protected  class EchoHandler implements RequestHandler {
        public boolean accessed = false;

        @Override
        public RawResponse handle(Request request) {
            request.getArg2().retain();
            request.getArg3().retain();
            RawResponse response = new RawResponse.Builder(request)
                .setArg2(request.getArg2())
                .setArg3(request.getArg3())
                .build();

            accessed = true;
            return response;
        }
    }
}


