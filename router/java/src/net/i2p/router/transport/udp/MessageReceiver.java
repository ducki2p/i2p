package net.i2p.router.transport.udp;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.router.RouterContext;
//import net.i2p.util.ByteCache;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Pull fully completed fragments off the {@link InboundMessageFragments} queue,
 * parse 'em into I2NPMessages, and stick them on the 
 * {@link net.i2p.router.InNetMessagePool} by way of the {@link UDPTransport}.
 */
class MessageReceiver {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    /** list of messages (InboundMessageState) fully received but not interpreted yet */
    private final BlockingQueue<InboundMessageState> _completeMessages;
    private boolean _alive;
    //private ByteCache _cache;
    private static final int MIN_THREADS = 2;  // unless < 32MB
    private static final int MAX_THREADS = 5;
    private final int _threadCount;
    private static final long POISON_IMS = -99999999999l;
    
    public MessageReceiver(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageReceiver.class);
        _transport = transport;
        _completeMessages = new LinkedBlockingQueue();

        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 96*1024*1024l;
        if (maxMemory < 32*1024*1024)
            _threadCount = 1;
        else if (maxMemory < 64*1024*1024)
            _threadCount = 2;
        else
            _threadCount = Math.max(MIN_THREADS, Math.min(MAX_THREADS, ctx.bandwidthLimiter().getInboundKBytesPerSecond() / 20));

        // the runners run forever, no need to have a cache
        //_cache = ByteCache.getInstance(64, I2NPMessage.MAX_SIZE);
        _context.statManager().createRateStat("udp.inboundExpired", "How many messages were expired before reception?", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.inboundRemaining", "How many messages were remaining when a message is pulled off the complete queue?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.inboundReady", "How many messages were ready when a message is added to the complete queue?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.inboundReadTime", "How long it takes to parse in the completed fragments into a message?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.inboundReceiveProcessTime", "How long it takes to add the message to the transport?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.inboundLag", "How long the olded ready message has been sitting on the queue (period is the queue size)?", "udp", UDPTransport.RATES);
        
        _alive = true;
    }
    
    public void startup() {
        _alive = true;
        for (int i = 0; i < _threadCount; i++) {
            I2PThread t = new I2PThread(new Runner(), "UDP message receiver " + (i+1) + '/' + _threadCount, true);
            t.start();
        }
    }
    
    private class Runner implements Runnable {
        private I2NPMessageHandler _handler;
        public Runner() { _handler = new I2NPMessageHandler(_context); }
        public void run() { loop(_handler); }
    }
    
    public void shutdown() {
        _alive = false;
        _completeMessages.clear();
        for (int i = 0; i < _threadCount; i++) {
            InboundMessageState ims = new InboundMessageState(_context, POISON_IMS, null);
            _completeMessages.offer(ims);
        }
        for (int i = 1; i <= 5 && !_completeMessages.isEmpty(); i++) {
            try {
                Thread.sleep(i * 50);
            } catch (InterruptedException ie) {}
        }
        _completeMessages.clear();
    }
    
    public void receiveMessage(InboundMessageState state) {
        //int total = 0;
        //long lag = -1;
        if (_alive)
            _completeMessages.offer(state);
        //total = _completeMessages.size();
        //if (total > 1)
        //    lag = ((InboundMessageState)_completeMessages.get(0)).getLifetime();
        //if (total > 1)
        //    _context.statManager().addRateData("udp.inboundReady", total, 0);
        //if (lag > 1000)
        //    _context.statManager().addRateData("udp.inboundLag", lag, total);
    }
    
    public void loop(I2NPMessageHandler handler) {
        InboundMessageState message = null;
        //ByteArray buf = _cache.acquire();
        ByteArray buf = new ByteArray(new byte[I2NPMessage.MAX_SIZE]);
        while (_alive) {
            int expired = 0;
            long expiredLifetime = 0;
            int remaining = 0;
            try {
                    while (message == null) {
                        message = _completeMessages.take();
                        if ( (message != null) && (message.getMessageId() == POISON_IMS) ) {
                            message = null;
                            break;
                        }
                        if ( (message != null) && (message.isExpired()) ) {
                            expiredLifetime += message.getLifetime();
                            message = null;
                            expired++;
                        }
                        //remaining = _completeMessages.size();
                }
            } catch (InterruptedException ie) {}
            
            if (expired > 0)
                _context.statManager().addRateData("udp.inboundExpired", expired, expiredLifetime);
            
            if (message != null) {
                long before = System.currentTimeMillis();
                //if (remaining > 0)
                //    _context.statManager().addRateData("udp.inboundRemaining", remaining, 0);
                int size = message.getCompleteSize();
                if (_log.shouldLog(Log.INFO))
                    _log.info("Full message received (" + message.getMessageId() + ") after " + message.getLifetime());
                long afterRead = -1;
                try {
                    I2NPMessage msg = readMessage(buf, message, handler);
                    afterRead = System.currentTimeMillis();
                    if (msg != null)
                        _transport.messageReceived(msg, null, message.getFrom(), message.getLifetime(), size);
                } catch (RuntimeException re) {
                    _log.error("b0rked receiving a message.. wazza huzza hmm?", re);
                    continue;
                }
                message = null;
                long after = System.currentTimeMillis();
                if (afterRead - before > 100)
                    _context.statManager().addRateData("udp.inboundReadTime", afterRead - before, remaining);
                if (after - afterRead > 100)
                    _context.statManager().addRateData("udp.inboundReceiveProcessTime", after - afterRead, remaining);
            }
        }
        
        // no need to zero it out, as these buffers are only used with an explicit getCompleteSize
        //_cache.release(buf, false); 
    }
    
    private I2NPMessage readMessage(ByteArray buf, InboundMessageState state, I2NPMessageHandler handler) {
        try {
            //byte buf[] = new byte[state.getCompleteSize()];
            ByteArray fragments[] = state.getFragments();
            int numFragments = state.getFragmentCount();
            int off = 0;
            for (int i = 0; i < numFragments; i++) {
                System.arraycopy(fragments[i].getData(), 0, buf.getData(), off, fragments[i].getValid());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Raw fragment[" + i + "] for " + state.getMessageId() + ": " 
                               + Base64.encode(fragments[i].getData(), 0, fragments[i].getValid())
                               + " (valid: " + fragments[i].getValid() 
                               + " raw: " + Base64.encode(fragments[i].getData()) + ")");
                off += fragments[i].getValid();
            }
            if (off != state.getCompleteSize()) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Hmm, offset of the fragments = " + off + " while the state says " + state.getCompleteSize());
                return null;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Raw byte array for " + state.getMessageId() + ": " + Base64.encode(buf.getData(), 0, state.getCompleteSize()));
            I2NPMessage m = I2NPMessageImpl.fromRawByteArray(_context, buf.getData(), 0, state.getCompleteSize(), handler);
            m.setUniqueId(state.getMessageId());
            return m;
        } catch (I2NPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Message invalid: " + state, ime);
            _context.messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(), "error: " + ime.toString() + ": " + state.toString());
            return null;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error dealing with a message: " + state, e);
            _context.messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(), "error: " + e.toString() + ": " + state.toString());
            return null;
        } finally {
            state.releaseResources();
        }
    }
}
