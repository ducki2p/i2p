package net.i2p.util;

import java.util.Random;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

import org.xlattice.crypto.filters.BloomSHA1;

/**
 * Series of bloom filters which decay over time, allowing their continual use
 * for time sensitive data.  This has a fixed size (currently 1MB per decay 
 * period, using two periods overall), allowing this to pump through hundreds of
 * entries per second with virtually no false positive rate.  Down the line, 
 * this may be refactored to allow tighter control of the size necessary for the
 * contained bloom filters, but a fixed 2MB overhead isn't that bad.
 *
 * NOTE: At 1MBps, the tunnel IVV will see an unacceptable false positive rate
 * of almost 0.1% with the current m and k values; however using DHS instead will use 30MB.
 * Further analysis and tweaking for the tunnel IVV may be required.
 */
public class DecayingBloomFilter {
    protected final I2PAppContext _context;
    protected final Log _log;
    private BloomSHA1 _current;
    private BloomSHA1 _previous;
    protected final int _durationMs;
    protected final int _entryBytes;
    private byte _extenders[][];
    private byte _extended[];
    private byte _longToEntry[];
    private long _longToEntryMask;
    protected long _currentDuplicates;
    protected volatile boolean _keepDecaying;
    protected SimpleTimer.TimedEvent _decayEvent;
    /** just for logging */
    protected final String _name;
    
    private static final int DEFAULT_M = 23;
    private static final int DEFAULT_K = 11;
    private static final boolean ALWAYS_MISS = false;
   
    /** only for extension by DHS */
    protected DecayingBloomFilter(int durationMs, int entryBytes, String name, I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(getClass());
        _entryBytes = entryBytes;
        _name = name;
        _durationMs = durationMs;
    }

    /**
     * Create a bloom filter that will decay its entries over time.  
     *
     * @param durationMs entries last for at least this long, but no more than twice this long
     * @param entryBytes how large are the entries to be added?  if this is less than 32 bytes,
     *                   the entries added will be expanded by concatenating their XORing 
     *                   against with sufficient random values.
     */
    public DecayingBloomFilter(I2PAppContext context, int durationMs, int entryBytes) {
        this(context, durationMs, entryBytes, "DBF");
    }

    /** @param name just for logging / debugging / stats */
    public DecayingBloomFilter(I2PAppContext context, int durationMs, int entryBytes, String name) {
        // this is instantiated in four different places, they may have different
        // requirements, but for now use this as a gross method of memory reduction.
        // m == 23 => 1MB each BloomSHA1 (4 pairs = 8MB total)
        this(context, durationMs, entryBytes, name, context.getProperty("router.decayingBloomFilterM", DEFAULT_M));
    }

    /** @param m filter size exponent */
    public DecayingBloomFilter(I2PAppContext context, int durationMs, int entryBytes, String name, int m) {
        _context = context;
        _log = context.logManager().getLog(DecayingBloomFilter.class);
        _entryBytes = entryBytes;
        _name = name;
        int k = DEFAULT_K;
        // max is (23,11) or (26,10); see KeySelector for details
        if (m > DEFAULT_M)
            k--;
        _current = new BloomSHA1(m, k);
        _previous = new BloomSHA1(m, k);
        _durationMs = durationMs;
        int numExtenders = (32+ (entryBytes-1))/entryBytes - 1;
        if (numExtenders < 0)
            numExtenders = 0;
        _extenders = new byte[numExtenders][entryBytes];
        for (int i = 0; i < numExtenders; i++)
            _context.random().nextBytes(_extenders[i]);
        if (numExtenders > 0) {
            _extended = new byte[32];
            _longToEntry = new byte[_entryBytes];
            _longToEntryMask = (1l << (_entryBytes * 8l)) -1;
        }
        _decayEvent = new DecayEvent();
        _keepDecaying = true;
        SimpleTimer.getInstance().addEvent(_decayEvent, _durationMs);
        if (_log.shouldLog(Log.WARN))
           _log.warn("New DBF " + name + " m = " + m + " k = " + k + " entryBytes = " + entryBytes +
                     " numExtenders = " + numExtenders + " cycle (s) = " + (durationMs / 1000));
        // try to get a handle on memory usage vs. false positives
        context.statManager().createRateStat("router.decayingBloomFilter." + name + ".size",
             "Size", "Router", new long[] { Math.max(60*1000, durationMs) });
        context.statManager().createRateStat("router.decayingBloomFilter." + name + ".dups",
             "1000000 * Duplicates/Size", "Router", new long[] { Math.max(60*1000, durationMs) });
        context.statManager().createRateStat("router.decayingBloomFilter." + name + ".log10(falsePos)",
             "log10 of the false positive rate (must have net.i2p.util.DecayingBloomFilter=DEBUG)",
             "Router", new long[] { Math.max(60*1000, durationMs) });
    }
    
    public long getCurrentDuplicateCount() { return _currentDuplicates; }

    public int getInsertedCount() { 
        synchronized (this) {
            return _current.size() + _previous.size(); 
        }
    }

    public double getFalsePositiveRate() { 
        synchronized (this) {
            return _current.falsePositives(); 
        }
    }
    
    /** 
     * @return true if the entry added is a duplicate
     */
    public boolean add(byte entry[]) {
        return add(entry, 0, entry.length);
    }

    /** 
     * @return true if the entry added is a duplicate
     */
    public boolean add(byte entry[], int off, int len) {
        if (ALWAYS_MISS) return false;
        if (entry == null) 
            throw new IllegalArgumentException("Null entry");
        if (len != _entryBytes) 
            throw new IllegalArgumentException("Bad entry [" + len + ", expected " 
                                               + _entryBytes + "]");
        synchronized (this) {
            return locked_add(entry, off, len, true);
        }
    }
    
    /** 
     * @return true if the entry added is a duplicate.  the number of low order 
     * bits used is determined by the entryBytes parameter used on creation of the
     * filter.
     *
     */
    public boolean add(long entry) {
        if (ALWAYS_MISS) return false;
        if (_entryBytes <= 7)
            entry = ((entry ^ _longToEntryMask) & ((1 << 31)-1)) | (entry ^ _longToEntryMask);
            //entry &= _longToEntryMask; 
        if (entry < 0) {
            DataHelper.toLong(_longToEntry, 0, _entryBytes, 0-entry);
            _longToEntry[0] |= (1 << 7);
        } else {
            DataHelper.toLong(_longToEntry, 0, _entryBytes, entry);
        }
        synchronized (this) {
            return locked_add(_longToEntry, 0, _longToEntry.length, true);
        }
    }
    
    /** 
     * @return true if the entry is already known.  this does NOT add the
     * entry however.
     *
     */
    public boolean isKnown(long entry) {
        if (ALWAYS_MISS) return false;
        if (_entryBytes <= 7)
            entry = ((entry ^ _longToEntryMask) & ((1 << 31)-1)) | (entry ^ _longToEntryMask); 
        if (entry < 0) {
            DataHelper.toLong(_longToEntry, 0, _entryBytes, 0-entry);
            _longToEntry[0] |= (1 << 7);
        } else {
            DataHelper.toLong(_longToEntry, 0, _entryBytes, entry);
        }
        synchronized (this) {
            return locked_add(_longToEntry, 0, _longToEntry.length, false);
        }
    }
    
    private boolean locked_add(byte entry[], int offset, int len, boolean addIfNew) {
        if (_extended != null) {
            // extend the entry to 32 bytes
            System.arraycopy(entry, offset, _extended, 0, len);
            for (int i = 0; i < _extenders.length; i++)
                DataHelper.xor(entry, offset, _extenders[i], 0, _extended, _entryBytes * (i+1), _entryBytes);

            boolean seen = _current.locked_member(_extended);
            seen = seen || _previous.locked_member(_extended);
            if (seen) {
                _currentDuplicates++;
                return true;
            } else {
                if (addIfNew) {
                    _current.locked_insert(_extended);
                }
                return false;
            }
        } else {
            boolean seen = _current.locked_member(entry, offset, len);
            seen = seen || _previous.locked_member(entry, offset, len);
            if (seen) {
                _currentDuplicates++;
                return true;
            } else {
                if (addIfNew) {
                    _current.locked_insert(entry, offset, len);
                }
                return false;
            }
        }
    }
    
    public void clear() {
        synchronized (this) {
            _current.clear();
            _previous.clear();
            _currentDuplicates = 0;
        }
    }
    
    public void stopDecaying() {
        _keepDecaying = false;
        SimpleTimer.getInstance().removeEvent(_decayEvent);
    }
    
    private void decay() {
        int currentCount = 0;
        long dups = 0;
        double fpr = 0d;
        synchronized (this) {
            BloomSHA1 tmp = _previous;
            currentCount = _current.size();
            if (_log.shouldLog(Log.DEBUG) && currentCount > 0)
                fpr = _current.falsePositives();
            _previous = _current;
            _current = tmp;
            _current.clear();
            dups = _currentDuplicates;
            _currentDuplicates = 0;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Decaying the filter " + _name + " after inserting " + currentCount 
                       + " elements and " + dups + " false positives with FPR = " + fpr);
        _context.statManager().addRateData("router.decayingBloomFilter." + _name + ".size",
                                           currentCount, 0);
        if (currentCount > 0)
            _context.statManager().addRateData("router.decayingBloomFilter." + _name + ".dups",
                                               1000l*1000*dups/currentCount, 0);
        if (fpr > 0d) {
            // only if log.shouldLog(Log.DEBUG) ...
            long exponent = (long) Math.log10(fpr);
            _context.statManager().addRateData("router.decayingBloomFilter." + _name + ".log10(falsePos)",
                                               exponent, 0);
        }
    }
    
    private class DecayEvent implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (_keepDecaying) {
                decay();
                SimpleTimer.getInstance().addEvent(DecayEvent.this, _durationMs);
            }
        }
    }
    
    /**
     *  This filter is used only for participants and OBEPs, not
     *  IBGWs, so depending on your assumptions of avg. tunnel length,
     *  the performance is somewhat better than the gross share BW
     *  would indicate.
     *
     *  Following stats for m=23, k=11:
     *  Theoretical false positive rate for   16 KBps: 1.17E-21
     *  Theoretical false positive rate for   24 KBps: 9.81E-20
     *  Theoretical false positive rate for   32 KBps: 2.24E-18
     *  Theoretical false positive rate for  256 KBps: 7.45E-9
     *  Theoretical false positive rate for  512 KBps: 5.32E-6
     *  Theoretical false positive rate for 1024 KBps: 1.48E-3
     *  Then it gets bad: 1280 .67%; 1536 2.0%; 1792 4.4%; 2048 8.2%.
     *
     *  Following stats for m=24, k=10:
     *  1280 4.5E-5; 1792 5.6E-4; 2048 0.14%
     *
     *  Following stats for m=25, k=10:
     *  1792 2.4E-6; 4096 0.14%
     */
    public static void main(String args[]) {
        int kbps = 256;
        int iterations = 10;
        testByLong(kbps, iterations);
        testByBytes(kbps, iterations);
    }
    private static void testByLong(int kbps, int numRuns) {
        int messages = 60 * 10 * kbps;
        Random r = new Random();
        DecayingBloomFilter filter = new DecayingBloomFilter(I2PAppContext.getGlobalContext(), 600*1000, 8);
        int falsePositives = 0;
        long totalTime = 0;
        double fpr = 0d;
        for (int j = 0; j < numRuns; j++) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < messages; i++) {
                if (filter.add(r.nextLong())) {
                    falsePositives++;
                    System.out.println("False positive " + falsePositives + " (testByLong j=" + j + " i=" + i + ")");
                }
            }
            totalTime += System.currentTimeMillis() - start;
            fpr = filter.getFalsePositiveRate();
            filter.clear();
        }
        filter.stopDecaying();
        System.out.println("False postive rate should be " + fpr);
        System.out.println("After " + numRuns + " runs pushing " + messages + " entries in "
                           + DataHelper.formatDuration(totalTime/numRuns) + " per run, there were "
                           + falsePositives + " false positives");

    }
    private static void testByBytes(int kbps, int numRuns) {
        byte iv[][] = new byte[60*10*kbps][16];
        Random r = new Random();
        for (int i = 0; i < iv.length; i++)
            r.nextBytes(iv[i]);

        DecayingBloomFilter filter = new DecayingBloomFilter(I2PAppContext.getGlobalContext(), 600*1000, 16);
        int falsePositives = 0;
        long totalTime = 0;
        double fpr = 0d;
        for (int j = 0; j < numRuns; j++) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < iv.length; i++) {
                if (filter.add(iv[i])) {
                    falsePositives++;
                    System.out.println("False positive " + falsePositives + " (testByBytes j=" + j + " i=" + i + ")");
                }
            }
            totalTime += System.currentTimeMillis() - start;
            fpr = filter.getFalsePositiveRate();
            filter.clear();
        }
        filter.stopDecaying();
        System.out.println("False postive rate should be " + fpr);
        System.out.println("After " + numRuns + " runs pushing " + iv.length + " entries in "
                           + DataHelper.formatDuration(totalTime/numRuns) + " per run, there were "
                           + falsePositives + " false positives");
        //System.out.println("inserted: " + bloom.size() + " with " + bloom.capacity() 
        //                   + " (" + bloom.falsePositives()*100.0d + "% false positive)");
    }
}
