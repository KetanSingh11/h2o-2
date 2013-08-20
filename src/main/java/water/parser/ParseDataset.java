package water.parser;

import java.io.EOFException;
import java.io.InputStream;
import java.util.*;
import java.util.zip.*;

import jsr166y.CountedCompleter;
import water.*;
import water.H2O.H2OCountedCompleter;
import water.fvec.Frame;
import water.parser.CustomParser.PSetupGuess;
import water.parser.CustomParser.ParserSetup;
import water.parser.CustomParser.ParserType;
import water.parser.DParseTask.Pass;
import water.util.*;
import water.util.Utils.IcedArrayList;

import com.google.common.base.Throwables;
import com.google.common.io.Closeables;

/**
 * Helper class to parse an entire ValueArray data, and produce a structured ValueArray result.
 *
 * @author <a href="mailto:cliffc@0xdata.com"></a>
 */
@SuppressWarnings("fallthrough")
public final class ParseDataset extends Job {
  public static enum Compression { NONE, ZIP, GZIP }

  public static int PLIMIT = Integer.MAX_VALUE;

  public final Key  _progress;

  private ParseDataset(Key dest, Key[] keys) {
    super("Parse", dest);
    Value dataset = DKV.get(keys[0]);
    long total = dataset.length() * Pass.values().length;
    for(int i = 1; i < keys.length; ++i){
      dataset = DKV.get(keys[i]);
      total += dataset.length() * Pass.values().length;
    }
    _progress = Key.make(UUID.randomUUID().toString(), (byte) 0, Key.JOB);
    UKV.put(_progress, new Progress(0,total));
  }

  public static PSetupGuess guessSetup(byte [] bits){
    return guessSetup(bits,new ParserSetup(),true);
  }


  public static class GuessSetupTsk extends MRTask<GuessSetupTsk> {
    final CustomParser.ParserSetup _userSetup;
    final boolean _checkHeader;
    Key _setupFromFile;
    Key _hdrFromFile;
    PSetupGuess _gSetup;
    IcedArrayList<Key> _failedSetup;
    IcedArrayList<Key> _conflicts;

    public GuessSetupTsk(CustomParser.ParserSetup userSetup, boolean checkHeader){
      _userSetup = userSetup;
      assert _userSetup != null;
      _checkHeader = checkHeader;
      assert !_userSetup._header || !checkHeader;
    }
    public static final int MAX_ERRORS = 64;
    @Override public void map(Key key) {
      _failedSetup = new IcedArrayList<Key>();
      _conflicts = new IcedArrayList<Key>();
      byte [] bits = Utils.getFirstUnzipedBytes(key);
      _gSetup = ParseDataset.guessSetup(bits, _userSetup, _checkHeader);
      if(_gSetup == null || !_gSetup.valid())
        _failedSetup.add(key);
      else {
        _setupFromFile = key;
        if(_checkHeader && _gSetup._setup._header)
          _hdrFromFile = key;
      }
    }

    @Override public void reduce(GuessSetupTsk drt) {
      if(_gSetup == null || !_gSetup.valid()){
        _gSetup = drt._gSetup;
        _hdrFromFile = drt._hdrFromFile;
        _setupFromFile = drt._setupFromFile;
      } else if(drt._gSetup.valid() && !_gSetup._setup.isCompatible(drt._gSetup._setup) ){
        _conflicts.add(_setupFromFile);
        _conflicts.add(drt._setupFromFile);
        if(Math.random() > 0.5){
          _gSetup = drt._gSetup; // setups are not compatible, select random setup to send up (thus, the most common setup should make it to the top)
          _setupFromFile = drt._setupFromFile;
          _hdrFromFile = drt._hdrFromFile;
        }
      } else if(drt._gSetup.valid()){ // merge the two setups
        if(!_gSetup._setup._header && drt._gSetup._setup._header){
          _gSetup._setup._header = true;
          _hdrFromFile = drt._hdrFromFile;
          _gSetup._setup._columnNames = drt._gSetup._setup._columnNames;
        }
        if(_gSetup._data.length < CustomParser.MAX_PREVIEW_LINES){
          int n = _gSetup._data.length;
          int m = Math.min(CustomParser.MAX_PREVIEW_LINES, n + drt._gSetup._data.length-1);
          _gSetup._data = Arrays.copyOf(_gSetup._data, m);
          for(int i = n; i < m; ++i){
            _gSetup._data[i] = drt._gSetup._data[i-n+1];
          }
        }
      }
      // merge failures
      if(_failedSetup == null){
        _failedSetup = drt._failedSetup;
        _conflicts = drt._conflicts;
      } else {
        _failedSetup.addAll(drt._failedSetup);
        _conflicts.addAll(drt._conflicts);
      }
    }
  }

  public static CustomParser.PSetupGuess guessSetup(ArrayList<Key> keys,Key headerKey, CustomParser.ParserSetup setup, boolean checkHeader){
    String [] colNames = null;
    CustomParser.PSetupGuess gSetup = null;
    boolean headerKeyPartOfParse = false;
    if(headerKey != null ){
      if(keys.contains(headerKey)){
        headerKeyPartOfParse = true;
        keys.remove(headerKey); // process the header key separately
      }
    }
    if(keys.size() > 1){
      GuessSetupTsk t = new GuessSetupTsk(setup,checkHeader);
      Key [] ks = new Key[keys.size()];
      keys.toArray(ks);
      t.invoke(ks);
      gSetup = t._gSetup;
      if(!gSetup.valid())
        throw new IllegalArgumentException("<h3>Can not parse:</h3>" +( setup._pType == CustomParser.ParserType.AUTO?"Did not finad any matching consistent parser setup, please specify manually":"None of the files is consistent with the given setup!"));
      if((!t._failedSetup.isEmpty() || !t._conflicts.isEmpty())){
        StringBuilder sb = new StringBuilder();
        // run guess setup once more, this time knowing the global setup to get rid of conflicts (turns them into failures) and bogus failures (i.e. single line files with unexpected separator)
        GuessSetupTsk t2 = new GuessSetupTsk(gSetup._setup, !gSetup._setup._header);
        Key [] keys2 = new Key[t._conflicts.size() + t._failedSetup.size()];
        int i = 0;
        for(Key k:t._conflicts)keys2[i++] = k;
        for(Key k:t._failedSetup)keys2[i++] = k;
        t2.invoke(keys2);
        t._failedSetup = t2._failedSetup;
        t._conflicts = t2._conflicts;
      }
      assert t._conflicts.isEmpty(); // we should not have any conflicts here, either we failed to find any valid global setup, or conflicts should've been converted into failures in the second pass
      if(!t._failedSetup.isEmpty()){
        StringBuilder sb = new StringBuilder("<div>\n<b>Found " + t._failedSetup.size() + " files which are not compatible with the given setup:</b></div>");
        if(t._failedSetup.size() > 5){
          int n = t._failedSetup.size();
          sb.append("<div>" + t._failedSetup.get(0) + "</div>");
          sb.append("<div>" + t._failedSetup.get(1) + "</div>");
          sb.append("<div>...</div>");
          sb.append("<div>" + t._failedSetup.get(n-2) + "</div>");
          sb.append("<div>" + t._failedSetup.get(n-1) + "</div>");
        } else for(int i = 0; i < t._failedSetup.size();++i)
          sb.append("<div>" + t._failedSetup.get(i) + "</div>");
        throw new IllegalArgumentException("<h3>Can not parse:</h3>" + sb.toString());
      }
    } else
      gSetup = ParseDataset.guessSetup(Utils.getFirstUnzipedBytes(keys.get(0)),setup,checkHeader);
    if(headerKey != null){ // separate headerKey
      Value v = DKV.get(headerKey);
      if(!v.isRawData()){ // either ValueArray or a Frame, just extract the headers
        if(v.isArray()){
          ValueArray ary = v.get();
          colNames = ary.colNames();
        } else if(v.isFrame()){
          Frame fr = v.get();
          colNames = fr._names;
        } else
          throw new IllegalArgumentException("Headers can only come from unparsed data, ValueArray or a frame. Got " + v.newInstance().getClass().getSimpleName());
      } else { // check the hdr setup by parsing first bytes
        CustomParser.ParserSetup lSetup = gSetup._setup.clone();
        lSetup._header = true;
        PSetupGuess hSetup = ParseDataset.guessSetup(Utils.getFirstUnzipedBytes(headerKey),lSetup,false);
        if(hSetup == null || hSetup._setup._ncols != setup._ncols) { // no match with global setup, try once more with general setup (e.g. header file can have different separator than the rest)
          ParserSetup stp = new ParserSetup();
          stp._header = true;
          hSetup = ParseDataset.guessSetup(Utils.getFirstUnzipedBytes(headerKey),stp,false);
        }
        if(hSetup._data != null && hSetup._data.length > 1){// the hdr file had both hdr and data, it better be part of the parse and represent the global parser setup
          if(!headerKeyPartOfParse) throw new IllegalArgumentException(headerKey + " can not be used as a header file. Please either parse it separately first or include the file in the parse. Raw (unparsed) files can only be used as headers if they are included in the parse or they contain ONLY the header and NO DATA.");
          else if(gSetup._setup.isCompatible(hSetup._setup)){
            gSetup = hSetup;
            keys.add(headerKey); // put the key back so the file is parsed!
          }else
            throw new IllegalArgumentException("Header file is not compatible with the other files.");
        } else if(hSetup != null && hSetup._setup._columnNames != null)
          colNames = hSetup._setup._columnNames;
        else
          throw new IllegalArgumentException("Invalid header file. I did not find any column names.");
      }
    }
    // now set the header info in the final setup
    if(colNames != null){
      gSetup._setup._header = true;
      gSetup._setup._columnNames = colNames;
    }
    return gSetup;
  }



  public static PSetupGuess guessSetup(byte [] bits, ParserSetup setup, boolean checkHeader){
    ArrayList<PSetupGuess> guesses = new ArrayList<CustomParser.PSetupGuess>();
    PSetupGuess res = null;
    if(setup == null)setup = new ParserSetup();
    switch(setup._pType){
      case CSV:
        return CsvParser.guessSetup(bits,setup,checkHeader);
      case SVMLight:
        return SVMLightParser.guessSetup(bits);
      case XLS:
        return XlsParser.guessSetup(bits);
      case AUTO:
        try{
          if((res = XlsParser.guessSetup(bits)) != null && res.valid())
            if(!res.hasErrors())return res;
            else guesses.add(res);
        }catch(Exception e){}
        try{
          if((res = SVMLightParser.guessSetup(bits)) != null && res.valid())
            if(!res.hasErrors())return res;
            else guesses.add(res);
        }catch(Exception e){}
        try{
          if((res = CsvParser.guessSetup(bits,setup,checkHeader)) != null && res.valid())
            if(!res.hasErrors())return res;
            else guesses.add(res);
        }catch(Exception e){}
        return res;
      default:
        throw new IllegalArgumentException(setup._pType + " is not implemented.");
    }
  }

  public static void parse(Key okey, Key [] keys){
    forkParseDataset(okey, keys, null).get();
  }

  static DParseTask tryParseXls(Value v,ParseDataset job){
    DParseTask t =  new DParseTask().createPassOne(v, job, new XlsParser(null));
    try{t.passOne();} catch(Exception e) {return null;}
    return t;
  }

  public static void parse(ParseDataset job, Key [] keys, CustomParser.ParserSetup setup){
    if(setup == null){
      ArrayList<Key> ks = new ArrayList<Key>(keys.length);
      for (Key k:keys)ks.add(k);
      PSetupGuess guess = guessSetup(ks, null, new ParserSetup(), true);
      if(!guess.valid())throw new RuntimeException("can not parse ethis dataset, did not find working setup");
      setup = guess._setup;
    }
    int j = 0;
    UKV.remove(job.dest());// remove any previous instance and insert a sentinel (to ensure no one has been writing to the same keys during our parse!
    Key [] nonEmptyKeys = new Key[keys.length];
    for (int i = 0; i < keys.length; ++i) {
      Value v = DKV.get(keys[i]);
      if (v == null || v.length() > 0) // skip nonzeros
        nonEmptyKeys[j++] = keys[i];
    }
    if (j < nonEmptyKeys.length) // remove the nulls
      keys = Arrays.copyOf(nonEmptyKeys, j);
    if (keys.length == 0) {
      job.cancel();
      return;
    }
    if(setup == null || setup._pType == ParserType.XLS){
      DParseTask p1 = tryParseXls(DKV.get(keys[0]),job);
      if(p1 != null) {
        if(keys.length == 1){ // shortcut for 1 xls file, we already have pass one done, just do the 2nd pass and we're done
          DParseTask p2 = p1.createPassTwo();
          p2.passTwo();
          p2.createValueArrayHeader();
          job.remove();
          return;
        } else
          throw H2O.unimpl();
      }
    }
    Compression compression = Utils.guessCompressionMethod(DKV.get(keys[0]).getFirstBytes());
    try {
      UnzipAndParseTask tsk = new UnzipAndParseTask(job, compression, setup);
      tsk.invoke(keys);
      DParseTask [] p2s = new DParseTask[keys.length];
      DParseTask phaseTwo = tsk._tsk.createPassTwo();
      // too keep original order of the keys...
      HashMap<Key, FileInfo> fileInfo = new HashMap<Key, FileInfo>();
      long rowCount = 0;
      for(int i = 0; i < tsk._fileInfo.length; ++i)
        fileInfo.put(tsk._fileInfo[i]._ikey,tsk._fileInfo[i]);
      // run pass 2
      for(int i = 0; i < keys.length; ++i){
        FileInfo finfo = fileInfo.get(keys[i]);
        Key k = finfo._okey;
        long nrows = finfo._nrows[finfo._nrows.length-1];
        for(j = 0; j < finfo._nrows.length; ++j)
          finfo._nrows[j] += rowCount;
        rowCount += nrows;
        p2s[i] = phaseTwo.makePhase2Clone(finfo).dfork(k);
      }
      phaseTwo._sigma = new double[phaseTwo._ncolumns];
      phaseTwo._invalidValues = new long[phaseTwo._ncolumns];
      // now put the results together and create ValueArray header
      for(int i = 0; i < p2s.length; ++i){
        DParseTask t = p2s[i];
        p2s[i].get();
        for (j = 0; j < phaseTwo._ncolumns; ++j) {
          phaseTwo._sigma[j] += t._sigma[j];
          phaseTwo._invalidValues[j] += t._invalidValues[j];
        }
        if ((t._error != null) && !t._error.isEmpty()) {
          System.err.println(phaseTwo._error);
          throw new Exception("The dataset format is not recognized/supported");
        }
        FileInfo finfo = fileInfo.get(keys[i]);
        UKV.remove(finfo._okey);
      }
      phaseTwo.normalizeSigma();
      phaseTwo._colNames = setup._columnNames;
      if(setup._header)
        phaseTwo.setColumnNames(setup._columnNames);
      phaseTwo.createValueArrayHeader();
    } catch (Throwable e) {
      UKV.put(job.dest(), new Fail(e.getMessage()));
      throw Throwables.propagate(e);
    } finally {
      job.remove();
    }
  }

  public static class ParserFJTask extends H2OCountedCompleter {
    final ParseDataset job;
    Key [] keys;
    CustomParser.ParserSetup setup;

    public ParserFJTask(ParseDataset job, Key [] keys, CustomParser.ParserSetup setup){
      this.job = job;
      this.keys = keys;
      this.setup = setup;
    }
    @Override
    public void compute2() {
      parse(job, keys,setup);
      tryComplete();
    }
  }
  public static Job forkParseDataset(final Key dest, final Key[] keys, final CustomParser.ParserSetup setup) {
    ParseDataset job = new ParseDataset(dest, keys);
    H2OCountedCompleter fjt = new ParserFJTask(job, keys, setup);
    job.start(fjt);
    H2O.submitTask(fjt);
    return job;
  }
  public static class ParseException extends RuntimeException {
    public ParseException(String msg) {
      super(msg);
    }
  }
  public static class FileInfo extends Iced{
    Key _ikey;
    Key _okey;
    long [] _nrows;
    boolean _header;
  }

  public static class UnzipAndParseTask extends DRemoteTask {
    final ParseDataset _job;
    final Compression _comp;
    DParseTask _tsk;
    FileInfo [] _fileInfo;
    CustomParser.ParserSetup _parserSetup;

    public UnzipAndParseTask(ParseDataset job, Compression comp, CustomParser.ParserSetup parserSetup) {
      this(job,comp,parserSetup, Integer.MAX_VALUE);
    }
    public UnzipAndParseTask(ParseDataset job, Compression comp, CustomParser.ParserSetup parserSetup, int maxParallelism) {
      _job = job;
      _comp = comp;
      _parserSetup = parserSetup;
    }
    @Override
    public DRemoteTask dfork( Key... keys ) {
      _keys = keys;
      if(_parserSetup == null)
        _parserSetup = ParseDataset.guessSetup(Utils.getFirstUnzipedBytes(keys[0]))._setup;
      H2O.submitTask(this);
      return this;
    }
    static private class UnzipProgressMonitor implements ProgressMonitor {
      int _counter = 0;
      Key _progress;

      public UnzipProgressMonitor(Key progress){_progress = progress;}
      @Override
      public void update(long n) {
        n += _counter;
        if(n > (1 << 20)){
          onProgress(n, _progress);
          _counter = 0;
        } else
          _counter = (int)n;
      }
    }
    // actual implementation of unzip and parse, intended for the FJ computation
    private class UnzipAndParseLocalTask extends H2OCountedCompleter {
      final int _idx;
      public UnzipAndParseLocalTask(int idx){
        _idx = idx;
        setCompleter(UnzipAndParseTask.this);
      }
      protected  DParseTask _p1;
      @Override
      public void compute2() {
        final Key key = _keys[_idx];
        Value v = DKV.get(key);
        assert v != null;
        ParserSetup localSetup = ParseDataset.guessSetup(Utils.getFirstUnzipedBytes(v), _parserSetup,false)._setup;
        if(!_parserSetup.isCompatible(localSetup))throw new ParseException("Parsing incompatible files. " + _parserSetup.toString() + " is not compatible with " + localSetup.toString());
        _fileInfo[_idx] = new FileInfo();
        _fileInfo[_idx]._ikey = key;
        _fileInfo[_idx]._okey = key;
        if(localSetup._header &= _parserSetup._header) {
          assert localSetup._columnNames != null:"parsing " + key;
          assert _parserSetup._columnNames != null:"parsing " + key;
          for(int i = 0; i < _parserSetup._ncols; ++i)
            localSetup._header &= _parserSetup._columnNames[i].equalsIgnoreCase(localSetup._columnNames[i]);
        }
        _fileInfo[_idx]._header = localSetup._header;
        CustomParser parser = null;
        DParseTask dpt = null;
        switch(localSetup._pType){
          case CSV:
            parser = new CsvParser(localSetup, false);
            dpt = new DParseTask();
            break;
          case SVMLight:
            parser = new SVMLightParser(localSetup);
            dpt = new SVMLightDParseTask();
            break;
          default:
            throw H2O.unimpl();
        }
        long csz = v.length();
        if(_comp != Compression.NONE){
          onProgressSizeChange(csz,_job); // additional pass through the data to decompress
          InputStream is = null;
          InputStream ris = null;
          try {
            ris = v.openStream(new UnzipProgressMonitor(_job._progress));
            switch(_comp){
            case ZIP:
              ZipInputStream zis = new ZipInputStream(ris);
              ZipEntry ze = zis.getNextEntry();
              // There is at least one entry in zip file and it is not a directory.
              if (ze == null || ze.isDirectory())
                throw new Exception("Unsupported zip file: " + ((ze == null) ? "No entry found": "Files containing directory are not supported."));
              is = zis;
              break;
            case GZIP:
              is = new GZIPInputStream(ris);
              break;
            default:
              Log.info("Can't understand compression: _comp: "+_comp+" csz: "+csz+" key: "+key+" ris: "+ris);
              throw H2O.unimpl();
            }
            _fileInfo[_idx]._okey = Key.make(new String(key._kb) + "_UNZIPPED");
            ValueArray.readPut(_fileInfo[_idx]._okey, is,_job);
            v = DKV.get(_fileInfo[_idx]._okey);
            onProgressSizeChange(2*(v.length() - csz), _job); // the 2 passes will go over larger file!
            assert v != null;
          }catch (EOFException e){
            if(ris != null && ris instanceof RIStream){
              RIStream r = (RIStream)ris;
              System.err.println("Unexpected eof after reading " + r.off() + "bytes, expeted size = " + r.expectedSz());
            }
            System.err.println("failed decompressing data " + key.toString() + " with compression " + _comp);
            throw new RuntimeException(e);
          } catch (Throwable t) {
            System.err.println("failed decompressing data " + key.toString() + " with compression " + _comp);
            throw new RuntimeException(t);
          } finally {
           Closeables.closeQuietly(is);
          }
        }
        _p1 = dpt.createPassOne(v, _job, parser);
        _p1.setCompleter(this);
        _p1.passOne();
//        if(_parser instanceof CsvParser){
//          CustomParser p2 = null; // gues parser hereInspect.csvGuessValue(v);
//          if(setup._data[0].length != _ncolumns)
//            throw new ParseException("Found conflicting number of columns (using separator " + (int)_sep + ") when parsing multiple files. Found " + setup._data[0].length + " columns  in " + key + " , but expected " + _ncolumns);
//          _fileInfo[_idx]._header = setup._header;
//          if(_fileInfo[_idx]._header && _headers != null) // check if we have the header, it should be the same one as we got from the head
//            for(int i = 0; i < setup._data[0].length; ++i)
//              _fileInfo[_idx]._header = _fileInfo[_idx]._header && setup._data[0][i].equalsIgnoreCase(_headers[i]);
//          setup = new CsvParser.Setup(_sep, _fileInfo[_idx]._header, setup._data, setup._numlines, setup._bits);
//          _p1 = DParseTask.createPassOne(v, _job, _pType);
//          _p1.setCompleter(this);
//          _p1.passOne(setup);
          // DO NOT call tryComplete here, _p1 calls it!
//        } else {
//         _p1 = tryParseXls(v,_job);
//         if(_p1 == null)
//           throw new ParseException("Found conflicting types of files. Can not parse xls and not-xls files together");
//         tryComplete();
//        }
      }

      @Override
      public void onCompletion(CountedCompleter caller){
        try{
          _fileInfo[_idx]._nrows = _p1._nrows;
          long numRows = 0;
          for(int i = 0; i < _p1._nrows.length; ++i){
            numRows += _p1._nrows[i];
            _fileInfo[_idx]._nrows[i] = numRows;
          }
        }catch(Throwable t){t.printStackTrace();}
        quietlyComplete(); // wake up anyone  who is joining on this task!
      }
    }

    @Override
    public void lcompute() {
      try{
        _fileInfo = new FileInfo[_keys.length];
        subTasks = new UnzipAndParseLocalTask[_keys.length];
        setPendingCount(subTasks.length);
        int p = 0;
        int j = 0;
        for(int i = 0; i < _keys.length; ++i){
          if(p == ParseDataset.PLIMIT) subTasks[j++].join(); else ++p;
          H2O.submitTask((subTasks[i] = new UnzipAndParseLocalTask(i)));
        }
      }catch(Throwable t){t.printStackTrace();}
      tryComplete();
    }

    transient UnzipAndParseLocalTask [] subTasks;
    @Override
    public final void lonCompletion(CountedCompleter caller){
      try{
        _tsk = subTasks[0]._p1;
        for(int i = 1; i < _keys.length; ++i){
          DParseTask tsk = subTasks[i]._p1;
          tsk._nrows = _tsk._nrows;
          _tsk.reduce(tsk);
        }
      }catch(Throwable t){t.printStackTrace();}
    }

    @Override
    public void reduce(DRemoteTask drt) {
      try{
        UnzipAndParseTask tsk = (UnzipAndParseTask)drt;
        if(_tsk == null && _fileInfo == null){
          _fileInfo = tsk._fileInfo;
          _tsk = tsk._tsk;
        } else {
          final int n = _fileInfo.length;
          _fileInfo = Arrays.copyOf(_fileInfo, n + tsk._fileInfo.length);
          System.arraycopy(tsk._fileInfo, 0, _fileInfo, n, tsk._fileInfo.length);
          // we do not want to merge nrows from different files, apart from that, we want to use standard reduce!
          tsk._tsk._nrows = _tsk._nrows;
          _tsk.reduce(tsk._tsk);
        }
      }catch(Throwable t){t.printStackTrace();}
    }
  }

  // True if the array is all NaNs
  static boolean allNaNs(double ds[]) {
    for( double d : ds )
      if( !Double.isNaN(d) )
        return false;
    return true;
  }

  // Progress (TODO count chunks in VA, unify with models?)

  static class Progress extends Iced {
    long _total;
    long _value;
    Progress(long val, long total){_value = val; _total = total;}
  }

  @Override
  public float progress() {
    Progress progress = UKV.get(_progress);
    if(progress == null || progress._total == 0) return 0;
    return progress._value / (float) progress._total;
  }
  @Override public void remove() {
    DKV.remove(_progress);
    super.remove();
  }
  static final void onProgress(final Key chunk, final Key progress) {
    assert progress != null;
    Value val = DKV.get(chunk);
    if (val == null)
      return;
    final long len = val.length();
    onProgress(len, progress);
  }
  static final void onProgress(final long len, final Key progress) {
    new TAtomic<Progress>() {
      @Override
      public Progress atomic(Progress old) {
        if (old == null)
          return null;
        old._value += len;
        return old;
      }
    }.fork(progress);
  }
  static final void onProgressSizeChange(final long len, final ParseDataset job) {
    new TAtomic<Progress>() {
      @Override
      public Progress atomic(Progress old) {
        if (old == null)
          return null;
        old._total += len;
        return old;
      }
    }.fork(job._progress);
  }
}
