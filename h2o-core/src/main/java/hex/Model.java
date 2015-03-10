package hex;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import org.joda.time.DateTime;

import water.*;
import water.fvec.*;
import water.util.*;
import hex.genmodel.GenModel;

/**
 * A Model models reality (hopefully).
 * A model can be used to 'score' a row (make a prediction), or a collection of
 * rows on any compatible dataset - meaning the row has all the columns with the
 * same names as used to build the mode and any enum (categorical) columns can
 * be adapted.
 */
public abstract class Model<M extends Model<M,P,O>, P extends Model.Parameters, O extends Model.Output> extends Lockable<M> {

  public abstract interface DeepFeatures {
    public Frame scoreAutoEncoder(Frame frame, Key destination_key);
    public Frame scoreDeepFeatures(Frame frame, final int layer);
  }

  /** Different prediction categories for models.  NOTE: the values list in the API annotation ModelOutputSchema needs to match. */
  public static enum ModelCategory {
    Unknown,
    Binomial,
    Multinomial,
    Regression,
    Clustering,
    AutoEncoder,
    DimReduction
  }

  public final boolean isSupervised() { return _output.isSupervised(); }

  /** Model-specific parameter class.  Each model sub-class contains an
   *  instance of one of these containing its builder parameters, with
   *  model-specific parameters.  E.g. KMeansModel extends Model and has a
   *  KMeansParameters extending Model.Parameters; sample parameters include K,
   *  whether or not to normalize, max iterations and the initial random seed.
   *
   *  <p>The non-transient fields are input parameters to the model-building
   *  process, and are considered "first class citizens" by the front-end - the
   *  front-end will cache Parameters (in the browser, in JavaScript, on disk)
   *  and rebuild Parameter instances from those caches.
   */
  public abstract static class Parameters extends Iced {
    public Key<Frame> _destination_key;     // desired Key for this model (otherwise is autogenerated)
    public Key<Frame> _train;               // User-Key of the Frame the Model is trained on
    public Key<Frame> _valid;               // User-Key of the Frame the Model is validated on, if any
    // TODO: This field belongs in the front-end column-selection process and
    // NOT in the parameters - because this requires all model-builders to have
    // column strip/ignore code.
    public String[] _ignored_columns;// column names to ignore for training
    public boolean _dropNA20Cols;    // True if dropping cols > 20% NAs
    public boolean _dropConsCols;    // True if dropping constant and all NA cols

    // Scoring a model on a dataset is not free; sometimes it is THE limiting
    // factor to model building.  By default, partially built models are only
    // scored every so many major model iterations - throttled to limit scoring
    // costs to less than 10% of the build time.  This flag forces scoring for
    // every iteration, allowing e.g. more fine-grained progress reporting.
    public boolean _score_each_iteration;

    /** For classification models, the maximum size (in terms of classes) of
     *  the confusion matrix for it to be printed. This option is meant to
     *  avoid printing extremely large confusion matrices.  */
    public int _max_confusion_matrix_size = 20;

    // Public no-arg constructor for reflective creation
    public Parameters() { _dropNA20Cols = defaultDropNA20Cols();
                          _dropConsCols = defaultDropConsCols(); }

    /** @return the training frame instance */
    public final Frame train() { return _train.get(); }
    /** @return the validation frame instance, or null
     *  if a validation frame was not specified */
    public final Frame valid() { return _valid==null ? null : _valid.<Frame>get(); }

    /** Read-Lock both training and validation User frames. */
    public void read_lock_frames(Job job) {
      train().read_lock(job._key);
      if( _valid != null && !_train.equals(_valid) )
        valid().read_lock(job._key);
    }

    /** Read-UnLock both training and validation User frames. */
    public void read_unlock_frames(Job job) {
      Frame tr = train();
      if( tr != null ) tr.unlock(job._key);
      if( _valid != null && !_train.equals(_valid) )
        valid().unlock(job._key);
    }

    // Override in subclasses to change the default; e.g. true in GLM
    protected boolean defaultDropNA20Cols() { return false; }
    protected boolean defaultDropConsCols() { return true; }

    /** Type of missing columns during adaptation between train/test datasets
     *  Overload this method for models that have sparse data handling - a zero
     *  will preserve the sparseness.  Otherwise, NaN is used.
     *  @return real-valued number (can be NaN)  */
    protected double missingColumnsType() { return Double.NaN; }

    /**
     * Compute a checksum based on all non-transient non-static ice-able assignable fields (incl. inherited ones) which have @API annotations.
     * Sort the fields first, since reflection gives us the fields in random order and we don't want the checksum to be affected by the field order.
     * NOTE: if a field is added to a Parameters class the checksum will differ even when all the previous parameters have the same value.  If
     * a client wants backward compatibility they will need to compare parameter values explicitly.
     * @return checksum
     */
    protected long checksum_impl() {
      long xs = 0x600D;
      int count = 0;
      Field[] fields = Weaver.getWovenFields(this.getClass());
      Arrays.sort(fields,
                  new Comparator<Field>() {
                    public int compare(Field field1, Field field2) {
                      return field1.getName().compareTo(field2.getName());
                    }
                  });

      for (Field f : fields) {
        final long P = MathUtils.PRIMES[count % MathUtils.PRIMES.length];
        Class<?> c = f.getType();
        if (c.isArray()) {
          try {
            f.setAccessible(true);
            if (f.get(this) != null) {
              if (c.getComponentType() == Integer.TYPE){
                int[] arr = (int[]) f.get(this);
                xs ^= (0xDECAF + P * (long)Arrays.hashCode(arr));
              } else if (c.getComponentType() == Float.TYPE) {
                float[] arr = (float[]) f.get(this);
                xs ^= (0xDECAF + P * (long)Arrays.hashCode(arr));
              } else if (c.getComponentType() == Double.TYPE) {
                double[] arr = (double[]) f.get(this);
                xs ^= (0xDECAF + P * (long)Arrays.hashCode(arr));
              } else if (c.getComponentType() == Long.TYPE){
                long[] arr = (long[]) f.get(this);
                xs ^= (0xDECAF + P * (long)Arrays.hashCode(arr));
              } else {
                Object[] arr = (Object[]) f.get(this);
                xs ^= (0xDECAF + P * (long)Arrays.deepHashCode(arr));
              } //else lead to ClassCastException
            } else {
              xs ^= (0xDECAF + P);
            }
          } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
          } catch (ClassCastException t) {
            throw H2O.unimpl(); //no support yet for int[][] etc.
          }
        } else {
          try {
            f.setAccessible(true);
            if (f.get(this) != null) {
              xs ^= (0x1337 + P * (long)(f.get(this)).hashCode());
            } else {
              xs ^= (0x1337 + P);
            }
          } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
          }
        }
        count++;
      }
      xs ^= train().checksum() * (_valid == null ? 17 : valid().checksum());
      return xs;
    }
  }

  public P _parms; // TODO: move things around so that this can be protected

  public String [] _warnings = new String[0];

  public void addWarning(String s){
    _warnings = Arrays.copyOf(_warnings,_warnings.length+1);
    _warnings[_warnings.length-1] = s;
  }

  /** Model-specific output class.  Each model sub-class contains an instance
   *  of one of these containing its "output": the pieces of the model needed
   *  for scoring.  E.g. KMeansModel has a KMeansOutput extending Model.Output
   *  which contains the cluster centers.  The output also includes the names,
   *  domains and other fields which are determined at training time.  */
  public abstract static class Output extends Iced {
    /** Columns used in the model and are used to match up with scoring data
     *  columns.  The last name is the response column name (if any). */
    public String _names[];
    /** Returns number of input features (OK for most unsupervised methods, need to override for supervised!) */
    public int nfeatures() { return _names.length; }

    /** Categorical/factor/enum mappings, per column.  Null for non-enum cols.
     *  Columns match the post-init cleanup columns.  The last column holds the
     *  response col enums for SupervisedModels.  */
    public String _domains[][];

    /** List of all the associated ModelMetrics objects, so we can delete them
     *  when we delete this model. */
    public Key[] _model_metrics = new Key[0];

    /** Job state (CANCELLED, FAILED, DONE).  TODO: Really the whole Job
     *  (run-time, etc) but that has to wait until Job is split from
     *  ModelBuilder. */
    public Job.JobState _state;

    /** Any final prep-work just before model-building starts, but after the
     *  user has clicked "go".  E.g., converting a response column to an enum
     *  touches the entire column (can be expensive), makes a parallel vec
     *  (Key/Data leak management issues), and might throw IAE if there are too
     *  many classes. */
    public Output( ModelBuilder b ) {
      if( b.error_count() > 0 )
        throw new IllegalArgumentException(b.validationErrors());
      // Capture the data "shape" the model is valid on
      _names  = b._train.names  ();
      _domains= b._train.domains();
    }

    public boolean isSupervised() { return false; }
    /** The name of the response column (which is always the last column). */
    public String responseName() { return (getModelCategory() == ModelCategory.Regression || isClassifier()) ?  _names[  _names.length-1] : null; }
    /** The names of the levels for an enum (categorical) response column. */
    public String[] classNames() { assert isSupervised(); return _domains[_domains.length-1]; }
    /** Is this model a classification model? (v. a regression or clustering model) */
    public boolean isClassifier() { return isSupervised() && classNames() != null ; }
    public int nclasses() {
      assert isSupervised();
      String cns[] = classNames();
      return cns==null ? 1 : cns.length;
    }

    // Note: some algorithms MUST redefine this method to return other model categories
    public ModelCategory getModelCategory() {
      return (isClassifier() ?
              (nclasses() > 2 ? ModelCategory.Multinomial : ModelCategory.Binomial) :
              ModelCategory.Regression);
    }

    // TODO: Needs to be Atomic update, not just synchronized
    public synchronized ModelMetrics addModelMetrics(ModelMetrics mm) {
      for( Key key : _model_metrics ) // Dup removal
        if( key==mm._key ) return mm;
      _model_metrics = Arrays.copyOf(_model_metrics, _model_metrics.length + 1);
      _model_metrics[_model_metrics.length - 1] = mm._key;
      return mm;                // Flow coding
    }

    long checksum_impl() {
      return (null == _names ? 13 : Arrays.hashCode(_names)) *
              (null == _domains ? 17 : Arrays.deepHashCode(_domains)) *
              getModelCategory().ordinal();
    }

    private void printTwoDimTables(StringBuilder sb, Object o) {
      for (Field f : Weaver.getWovenFields(o.getClass())) {
        Class<?> c = f.getType();
        if (c.isAssignableFrom(TwoDimTable.class)) {
          try {
            TwoDimTable t = (TwoDimTable) f.get(this);
            f.setAccessible(true);
            sb.append(t.toString());
          } catch (IllegalAccessException e) {
            e.printStackTrace();
          }
        }
      }
    }

    @Override public String toString() {
      StringBuilder sb = new StringBuilder();
      printTwoDimTables(sb, this);
      return sb.toString();
    }
  } // Output

  public O _output; // TODO: move things around so that this can be protected

  public ModelMetrics addMetrics(ModelMetrics mm) { return _output.addModelMetrics(mm); }

  public abstract ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain);

  /** Full constructor */
  public Model( Key selfKey, P parms, O output) {
    super(selfKey);
    _parms  = parms ;  assert parms  != null;
    _output = output;  // Output won't be set if we're assert output != null;
  }


  /** Adapt a Test/Validation Frame to be compatible for a Training Frame.  The
   *  intention here is that ModelBuilders can assume the test set has the same
   *  count of columns, and within each factor column the same set of
   *  same-numbered levels.  Extra levels are renumbered past those in the
   *  Train set but will still be present in the Test set, thus requiring
   *  range-checking.
   *
   *  This routine is used before model building (with no Model made yet) to
   *  check for compatible datasets, and also used to prepare a large dataset
   *  for scoring (with a Model).
   *
   *  Adaption does the following things:
   *  - Remove any "extra" Vecs appearing only in the test and not the train
   *  - Insert any "missing" Vecs appearing only in the train and not the test
   *    with all NAs ({@see missingColumnsType}).  This will issue a warning,
   *    and if the "expensive" flag is false won't actually make the column
   *    replacement column but instead will bail-out on the whole adaption (but
   *    will continue looking for more warnings).
   *  - If all columns are missing, issue an error.
   *  - Renumber matching cat levels to match the Train levels; this might make
   *    "holes" in the Test set cat levels, if some are not in the Test set.
   *  - Extra Test levels are renumbered past the end of the Train set, hence
   *    the train and test levels match up to all the train levels; there might
   *    be extra Test levels past that.
   *  - For all mis-matched levels, issue a warning.
   *
   *  The {@code test} frame is updated in-place to be compatible, by altering
   *  the names and Vecs; make a defensive copy if you do not want it modified.
   *  There is a fast-path cutout if the test set is already compatible.  Since
   *  the test-set is conditionally modifed with extra EnumWrappedVec optionally
   *  added it is recommended to use a Scope enter/exit to track Vec lifetimes.
   *
   *  @param test Testing Frame, updated in-place
   *  @param expensive Try hard to adapt; this might involve the creation of
   *  whole Vecs and thus get expensive.  If {@code false}, then only adapt if
   *  no warnings and errors; otherwise just the messages are produced.
   *  Created Vecs have to be deleted by the caller (e.g. Scope.enter/exit).
   *  @return Array of warnings; zero length (never null) for no warnings.
   *  Throws {@code IllegalArgumentException} if no columns are in common, or
   *  if any factor column has no levels in common.
   */
  public String[] adaptTestForTrain( Frame test, boolean expensive ) { return adaptTestForTrain( _output._names, _output._domains, test, _parms.missingColumnsType(), expensive); }
  /**
   *  @param names Training column names
   *  @param domains Training column levels
   *  @param missing Substitute for missing columns; usually NaN
   * */
  public static String[] adaptTestForTrain( String[] names, String[][] domains, Frame test, double missing, boolean expensive ) throws IllegalArgumentException {
    if( test == null) return new String[0];
    // Fast path cutout: already compatible
    String[][] tdomains = test.domains();
    if( names == test._names && domains == tdomains )
      return new String[0];
    // Fast path cutout: already compatible but needs work to test
    if( Arrays.equals(names,test._names) && Arrays.deepEquals(domains,tdomains) )
      return new String[0];

    // Build the validation set to be compatible with the training set.
    // Toss out extra columns, complain about missing ones, remap enums
    ArrayList<String> msgs = new ArrayList<>();
    Vec vvecs[] = new Vec[names.length];
    int good = 0;               // Any matching column names, at all?
    for( int i=0; i<names.length; i++ ) {
      Vec vec = test.vec(names[i]); // Search in the given validation set
      // If the training set is missing in the validation set, complain and
      // fill in with NAs.  If this is the response column for supervised
      // learners, it is still made.
      if( vec == null ) {
        msgs.add("Validation set is missing training column "+names[i]);
        if( expensive ) {
          vec = test.anyVec().makeCon(missing);
          vec.setDomain(domains[i]);
        }
      }
      if( vec != null ) {          // I have a column with a matching name
        if( domains[i] != null ) { // Model expects an enum
          if( vec.domain() != domains[i] && !Arrays.equals(vec.domain(),domains[i]) ) { // Result needs to be the same enum
            EnumWrappedVec evec = vec.adaptTo(domains[i]); // Convert to enum or throw IAE
            String[] ds = evec.domain();
            assert ds != null && ds.length >= domains[i].length;
            if (ds.length > domains[i].length)
              msgs.add("Validation column " + names[i] + " has levels not trained on: " + Arrays.toString(Arrays.copyOfRange(ds, domains[i].length, ds.length)));
            if (expensive) { vec = evec;  good++; } // Keep it
            else { evec.remove(); vec = null; } // No leaking if not-expensive
          } else {
            good++;
          }
        } else if( vec.isEnum() ) {
          throw new IllegalArgumentException("Validation set has categorical column "+names[i]+" which is real-valued in the training data");
        } else {
          good++;      // Assumed compatible; not checking e.g. Strings vs UUID
        }
      }
      vvecs[i] = vec;
    }
    if( good == 0 )
      throw new IllegalArgumentException("Validation set has no columns in common with the training set");
    if( good == names.length )  // Only update if got something for all columns
      test.restructure(names,vvecs);
    return msgs.toArray(new String[msgs.size()]);
  }

  /**
   * Bulk score the frame, and auto-name the resulting predictions frame.
   * @see #score(Frame, String)
   * @param fr frame which should be scored
   * @return A new frame containing a predicted values. For classification it
   *         contains a column with prediction and distribution for all
   *         response classes. For regression it contains only one column with
   *         predicted values.
   * @throws IllegalArgumentException
   */
  public Frame score(Frame fr) throws IllegalArgumentException {
    return score(fr, null);
  }

  /** Bulk score the frame {@code fr}, producing a Frame result; the 1st
   *  Vec is the predicted class, the remaining Vecs are the probability
   *  distributions.  For Regression (single-class) models, the 1st and only
   *  Vec is the prediction value.  The result is in the DKV; caller is
   *  responsible for deleting.
   *
   * @param fr frame which should be scored
   * @return A new frame containing a predicted values. For classification it
   *         contains a column with prediction and distribution for all
   *         response classes. For regression it contains only one column with
   *         predicted values.
   * @throws IllegalArgumentException
   */
  public Frame score(Frame fr, String destination_key) throws IllegalArgumentException {
    Frame adaptFr = new Frame(fr);
    adaptTestForTrain(adaptFr,true);   // Adapt
    Frame output = scoreImpl(fr,adaptFr, destination_key); // Score

    // Log modest confusion matrices
    Vec predicted = output.vecs()[0]; // Modeled/predicted response
    String mdomain[] = predicted.domain(); // Domain of predictions (union of test and train)

    // Output is in the model's domain, but needs to be mapped to the scored
    // dataset's domain.
    if( _output.isClassifier() ) {
//      assert(mdomain != null); // label must be enum
      ModelMetrics mm = ModelMetrics.getFromDKV(this,fr);
      ModelCategory model_cat = this._output.getModelCategory();
      ConfusionMatrix cm = mm.cm();
      if(model_cat == ModelCategory.Binomial)
        cm = ((ModelMetricsBinomial)mm)._cm;
      else if(model_cat == ModelCategory.Multinomial)
        cm = ((ModelMetricsMultinomial)mm)._cm;

      if (cm.domain != null) { //don't print table for regression
//        assert (java.util.Arrays.deepEquals(cm.domain,mdomain));
        cm.table = cm.toTable();
        if( cm.confusion_matrix.length < _parms._max_confusion_matrix_size/*Print size limitation*/ )
          water.util.Log.info(cm.table.toString(1));
      }

      Vec actual = fr.vec(_output.responseName());
      if( actual != null ) {  // Predict does not have an actual, scoring does
        String sdomain[] = actual.domain(); // Scored/test domain; can be null
        if (sdomain != null && mdomain != sdomain && !Arrays.equals(mdomain, sdomain))
          output.replace(0, new EnumWrappedVec(actual.group().addVec(), actual.get_espc(), sdomain, predicted._key));
      }
    }

    // Remove temp keys.  TODO: Really should use Scope but Scope does not
    // currently allow nested-key-keepers.
    Vec[] vecs = adaptFr.vecs();
    for( int i=0; i<vecs.length; i++ )
      if( fr.find(vecs[i]) != -1 ) // Exists in the original frame?
        vecs[i] = null;            // Do not delete it
    adaptFr.delete();
    return output;
  }

  /** Score an already adapted frame.  Returns a new Frame with new result
   *  vectors, all in the DKV.  Caller responsible for deleting.  Input is
   *  already adapted to the Model's domain, so the output is also.  Also
   *  computes the metrics for this frame.
   *
   * @param adaptFrm Already adapted frame
   * @return A Frame containing the prediction column, and class distribution
   */
  protected Frame scoreImpl(Frame fr, Frame adaptFrm, String destination_key) {
    assert Arrays.equals(_output._names,adaptFrm._names); // Already adapted
    // Build up the names & domains.
    final int nc = _output.nclasses();
    final int ncols = nc==1?1:nc+1; // Regression has 1 predict col; classification also has class distribution
    String[] names = new String[ncols];
    String[][] domains = new String[ncols][];
    names[0] = "predict";
    for(int i = 1; i < names.length; ++i)
      names[i] = _output.classNames()[i-1];
    domains[0] = nc==1 ? null : adaptFrm.lastVec().domain();
    // Score the dataset, building the class distribution & predictions
    BigScore bs = new BigScore(domains[0],ncols).doAll(ncols,adaptFrm);
    bs._mb.makeModelMetrics(this,fr, this instanceof SupervisedModel ? adaptFrm.lastVec().sigma() : Double.NaN);
    Frame res = bs.outputFrame((null == destination_key ? Key.make() : Key.make(destination_key)),names,domains);
    DKV.put(res);
    return res;
  }

  private class BigScore extends MRTask<BigScore> {
    final String[] _domain; // Prediction domain; union of test and train classes
    final int _npredcols;  // Number of columns in prediction; nclasses+1 - can be less than the prediction domain
    ModelMetrics.MetricBuilder _mb;
    BigScore( String[] domain, int ncols ) { _domain = domain; _npredcols = ncols; }
    @Override public void map( Chunk chks[], NewChunk cpreds[] ) {
      double[] tmp = new double[_output.nfeatures()];
      _mb = Model.this.makeMetricBuilder(_domain);
      int startcol = (_mb instanceof ModelMetricsSupervised.MetricBuilderSupervised ? chks.length-1 : 0); //columns of actual start here
      float[] preds = _mb._work;  // Sized for the union of test and train classes
      int len = chks[0]._len;
      for (int row = 0; row < len; row++) {
        float[] p = score0(chks, row, tmp, preds);
        float[] actual = new float[chks.length-startcol];
        for (int c = startcol; c < chks.length; c++) {
          actual[c-startcol] = (float)chks[c].atd(row);
        }
        _mb.perRow(preds, actual, Model.this);
        for (int c = 0; c < _npredcols; c++)  // Output predictions; sized for train only (excludes extra test classes)
          cpreds[c].addNum(p[c]);
      }
    }
    @Override public void reduce( BigScore bs ) { _mb.reduce(bs._mb); }

    @Override protected void postGlobal() { _mb.postGlobal(); }
  }

  /** Bulk scoring API for one row.  Chunks are all compatible with the model,
   *  and expect the last Chunks are for the final distribution and prediction.
   *  Default method is to just load the data into the tmp array, then call
   *  subclass scoring logic. */
  public float[] score0( Chunk chks[], int row_in_chunk, double[] tmp, float[] preds ) {
    assert chks.length>=_output._names.length;
    for( int i=0; i<_output._names.length; i++ )
      tmp[i] = chks[i].atd(row_in_chunk);
    return score0(tmp,preds);
  }

  /** Subclasses implement the scoring logic.  The data is pre-loaded into a
   *  re-used temp array, in the order the model expects.  The predictions are
   *  loaded into the re-used temp array, which is also returned.  */
  protected abstract float[] score0(double data[/*ncols*/], float preds[/*nclasses+1*/]);
  // Version where the user has just ponied-up an array of data to be scored.
  // Data must be in proper order.  Handy for JUnit tests.
  public double score(double [] data){ return ArrayUtils.maxIndex(score0(data, new float[_output.nclasses()]));  }

  @Override protected Futures remove_impl( Futures fs ) {
    if (_output._model_metrics != null)
      for( Key k : _output._model_metrics )
        k.remove(fs);
    return fs;
  }

  @Override protected long checksum_impl() { return _parms.checksum_impl() * _output.checksum_impl(); }

  // ==========================================================================
  /** Return a String which is a valid Java program representing a class that
   *  implements the Model.  The Java is of the form:
   *  <pre>
   *    class UUIDxxxxModel {
   *      public static final String NAMES[] = { ....column names... }
   *      public static final String DOMAINS[][] = { ....domain names... }
   *      // Pass in data in a double[], pre-aligned to the Model's requirements.
   *      // Jam predictions into the preds[] array; preds[0] is reserved for the
   *      // main prediction (class for classifiers or value for regression),
   *      // and remaining columns hold a probability distribution for classifiers.
   *      float[] predict( double data[], float preds[] );
   *      double[] map( HashMap &lt; String,Double &gt; row, double data[] );
   *      // Does the mapping lookup for every row, no allocation
   *      float[] predict( HashMap &lt; String,Double &gt; row, double data[], float preds[] );
   *      // Allocates a double[] for every row
   *      float[] predict( HashMap &lt; String,Double &gt; row, float preds[] );
   *      // Allocates a double[] and a float[] for every row
   *      float[] predict( HashMap &lt; String,Double &gt; row );
   *    }
   *  </pre>
   */
  public final String toJava() { return toJava(new SB()).toString(); }
  public SB toJava( SB sb ) {
    SB fileContext = new SB();  // preserve file context
    String modelName = JCodeGen.toJavaId(_key.toString());
    // HEADER
    sb.p("// AUTOGENERATED BY H2O at ").p(new DateTime().toString()).nl();
    sb.p("// ").p(H2O.ABV.projectVersion()).nl();
    sb.p("//").nl();
    sb.p("// Standalone prediction code with sample test data for ").p(this.getClass().getSimpleName()).p(" named ").p(modelName).nl();
    sb.p("//").nl();
    sb.p("// How to download, compile and execute:").nl();
    sb.p("//     mkdir tmpdir").nl();
    sb.p("//     cd tmpdir").nl();
    sb.p("//     curl http:/").p(H2O.SELF.toString()).p("/h2o-model.jar > h2o-model.jar").nl();
    sb.p("//     curl http:/").p(H2O.SELF.toString()).p("/2/").p(this.getClass().getSimpleName()).p("View.java?_modelKey=").pobj(_key).p(" > ").p(modelName).p(".java").nl();
    sb.p("//     javac -cp h2o-model.jar -J-Xmx2g -J-XX:MaxPermSize=128m ").p(modelName).p(".java").nl();
    sb.p("//     java -cp h2o-model.jar:. -Xmx2g -XX:MaxPermSize=256m -XX:ReservedCodeCacheSize=256m ").p(modelName).nl();
    sb.p("//").nl();
    sb.p("//     (Note:  Try java argument -XX:+PrintCompilation to show runtime JIT compiler behavior.)").nl();
    sb.p("import java.util.Map;").nl();
    sb.p("import hex.genmodel.GenModel;").nl();
    sb.nl();
    sb.p("public class ").p(modelName).p(" extends GenModel {").nl().ii(1);
    toJavaInit(sb, fileContext).nl();
    toJavaNAMES(sb);
    toJavaNCLASSES(sb);
    toJavaDOMAINS(sb, fileContext);
    toJavaPROB(sb);
    toJavaSuper(modelName,sb); //
    toJavaPredict(sb, fileContext);
    sb.p("}").nl().di(1);
    sb.p(fileContext).nl(); // Append file
    return sb;
  }
  /** Generate implementation for super class. */
  protected SB toJavaSuper( String modelName, SB sb ) {
    return sb.nl().ip("public "+modelName+"() { super(NAMES,DOMAINS); }").nl();
  }
  private SB toJavaNAMES( SB sb ) { return JCodeGen.toStaticVar(sb, "NAMES", Arrays.copyOf(_output._names,_output.nfeatures()), "Names of columns used by model."); }
  protected SB toJavaNCLASSES( SB sb ) { return _output.isClassifier() ? JCodeGen.toStaticVar(sb, "NCLASSES", _output.nclasses(), "Number of output classes included in training data response column.") : sb; }
  private SB toJavaDOMAINS( SB sb, SB fileContext ) {
    String modelName = JCodeGen.toJavaId(_key.toString());
    sb.nl();
    sb.ip("// Column domains. The last array contains domain of response column.").nl();
    sb.ip("public static final String[][] DOMAINS = new String[][] {").nl();
    for (int i=0; i<_output._domains.length; i++) {
      String[] dom = _output._domains[i];
      String colInfoClazz = modelName+"_ColInfo_"+i;
      sb.i(1).p("/* ").p(_output._names[i]).p(" */ ");
      sb.p(colInfoClazz).p(".VALUES");
      if (i!=_output._domains.length-1) sb.p(',');
      sb.nl();
      fileContext.ip("// The class representing column ").p(_output._names[i]).nl();
      JCodeGen.toClassWithArray(fileContext, null, colInfoClazz, dom);
    }
    return sb.ip("};").nl();
  }
  protected SB toJavaPROB( SB sb) { return sb; }
  // Override in subclasses to provide some top-level model-specific goodness
  protected SB toJavaInit(SB sb, SB fileContext) { return sb; }
  // Override in subclasses to provide some inside 'predict' call goodness
  // Method returns code which should be appended into generated top level class after
  // predict method.
  protected void toJavaPredictBody(SB body, SB cls, SB file) {
    throw new IllegalArgumentException("This model type does not support conversion to Java");
  }
  // Wrapper around the main predict call, including the signature and return value
  private SB toJavaPredict(SB ccsb, SB file) { // ccsb = classContext
    ccsb.nl();
    ccsb.ip("// Pass in data in a double[], pre-aligned to the Model's requirements.").nl();
    ccsb.ip("// Jam predictions into the preds[] array; preds[0] is reserved for the").nl();
    ccsb.ip("// main prediction (class for classifiers or value for regression),").nl();
    ccsb.ip("// and remaining columns hold a probability distribution for classifiers.").nl();
    ccsb.ip("public final float[] score0( double[] data, float[] preds ) {").nl();
    SB classCtxSb = new SB().ii(1);
    toJavaPredictBody(ccsb.ii(1), classCtxSb, file);
    ccsb.ip("return preds;").nl();
    ccsb.di(1).ip("}").nl();
    ccsb.p(classCtxSb);
    return ccsb;
  }

  // Convenience method for testing: build Java, convert it to a class &
  // execute it: compare the results of the new class's (JIT'd) scoring with
  // the built-in (interpreted) scoring on this dataset.  Returns true if all
  // is well, false is there are any mismatches.  Throws if there is any error
  // (typically an AssertionError or unable to compile the POJO).
  public boolean testJavaScoring( Frame data, Frame model_predictions ) {
    assert data.numRows()==model_predictions.numRows();
    Frame fr = null;
    try {
      fr = new Frame(data);
      String[] warns = adaptTestForTrain(fr,true);
      if( warns.length > 0 )
        System.err.println(Arrays.toString(warns));
      
      // Output is in the model's domain, but needs to be mapped to the scored
      // dataset's domain.
      int[] omap = null;
      if( _output.isClassifier() ) {
        Vec actual = fr.vec(_output.responseName());
        String sdomain[] = actual.domain(); // Scored/test domain; can be null
        String mdomain[] = model_predictions.vec(0).domain(); // Domain of predictions (union of test and train)
        if( sdomain != null && mdomain != sdomain && !Arrays.equals(mdomain, sdomain)) {
          EnumWrappedVec ewv = new EnumWrappedVec(mdomain,sdomain);
          omap = ewv.enum_map(); // Map from model-domain to scoring-domain
          ewv.remove();
        }
      }

      String modelName = JCodeGen.toJavaId(_key.toString());
      String java_text = toJava();
      //System.out.println(java_text);
      GenModel genmodel;
      try { 
        Class clz = JCodeGen.compile(modelName,java_text);
        genmodel = (GenModel)clz.newInstance();
      } catch( Exception e ) { throw H2O.fail("Internal POJO compilation failed",e); }

      Vec[] dvecs = fr.vecs();
      Vec[] pvecs = model_predictions.vecs();
    
      double features  [] = MemoryManager.malloc8d(genmodel._names.length);
      float predictions[] = MemoryManager.malloc4f(genmodel.nclasses()+1);

      // Compare predictions, counting mis-predicts
      int miss = 0;
      for( int row=0; row<fr.numRows(); row++ ) { // For all rows, single-threaded
        for( int col=0; col<features.length; col++ ) // Build feature set
          features[col] = dvecs[col].at(row);
        genmodel.score0(features,predictions);            // POJO predictions
        for( int col=0; col<predictions.length; col++ ) { // Compare predictions
          double d = pvecs[col].at(row);                  // Load internal scoring predictions
          if( col==0 && omap != null ) d = omap[(int)d];  // map enum response to scoring domain
          if( predictions[col] != d ) {                   // Compare predictions
            System.err.println("Predictions mismatch, row "+row+", col "+model_predictions._names[col]+", internal prediction="+d+", POJO prediction="+predictions[col]);
            if( miss++ > 10 ) return false; // Too many mispredicts, stop after 10
          }
        }
      }
      return miss==0;
    } finally {
      // Remove temp keys.  TODO: Really should use Scope but Scope does not
      // currently allow nested-key-keepers.
      Vec[] vecs = fr.vecs();
      for( int i=0; i<vecs.length; i++ )
        if( data.find(vecs[i]) != -1 ) // Exists in the original frame?
          vecs[i] = null;              // Do not delete it
      fr.delete();
    }
  }
}
