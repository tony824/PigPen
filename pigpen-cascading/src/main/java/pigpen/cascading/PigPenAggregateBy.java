package pigpen.cascading;

import java.util.Collection;
import java.util.List;

import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Var;

import cascading.flow.FlowProcess;
import cascading.operation.Aggregator;
import cascading.operation.AggregatorCall;
import cascading.operation.BaseOperation;
import cascading.operation.OperationCall;
import cascading.pipe.Pipe;
import cascading.pipe.assembly.AggregateBy;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;

public class PigPenAggregateBy extends AggregateBy {

  private static final Var GET_SEED_VALUE_FN = RT.var("pigpen.cascading.runtime", "get-seed-value");

  private static class Partial implements Functor {

    private final String init;
    private final String func;
    private final String argField;
    private transient IFn fn;
    private transient boolean allSentinel;
    private static final Var COMPUTE_PRE_FN = RT.var("pigpen.cascading.runtime", "aggregate-by-prepare");
    private static final Var COMPUTE_REDUCE_FN = RT.var("pigpen.cascading.runtime", "aggregate-by-reducef");

    private Partial(String init, String func, String argField) {
      this.init = init;
      this.func = func;
      this.argField = argField;
    }

    @Override
    public Fields getDeclaredFields() {
      return getFields(argField);
    }

    @Override
    public Tuple aggregate(FlowProcess flowProcess, TupleEntry args, Tuple context) {
      if (fn == null) {
        OperationUtil.init(init);
        fn = OperationUtil.getFn(func);
      }
      if (context == null) {
        allSentinel = true;
        context = new Tuple(GET_SEED_VALUE_FN.invoke(fn));
      }
      Object val = args.getObject(argField);
      if (OperationUtil.SENTINEL_VALUE.equals(val)) {
        return context;
      } else {
        allSentinel = false;
        Collection vals = (Collection)COMPUTE_PRE_FN.invoke(fn, OperationUtil.deserialize(val));
        return (Tuple)COMPUTE_REDUCE_FN.invoke(fn, vals, context.getObject(0));
      }
    }

    @Override
    public Tuple complete(FlowProcess flowProcess, Tuple context) {
      if (allSentinel) {
        return new Tuple(OperationUtil.SENTINEL_VALUE);
      } else {
        return new Tuple(OperationUtil.serialize(context.getObject(0)));
      }
    }
  }

  public static class Final extends BaseOperation<Final.Context> implements Aggregator<Final.Context> {

    private static final Var COMPUTE_PARTIAL_FN = RT.var("pigpen.cascading.runtime", "aggregate-by-combinef");
    private final String init;
    private final String func;
    private final String argField;

    public Final(String init, String func, String argField) {
      super(getFields(argField));
      this.init = init;
      this.func = func;
      this.argField = argField;
    }

    protected static class Context {
      private final IFn fn;
      private Object acc;
      private boolean allSentinel;

      public Context(IFn fn) {
        this.fn = fn;
      }

      public void reset() {
        acc = GET_SEED_VALUE_FN.invoke(fn);
        allSentinel = true;
      }
    }

    @Override
    public void prepare(FlowProcess flowProcess, OperationCall<Context> operationCall) {
      super.prepare(flowProcess, operationCall);
      OperationUtil.init(init);
      IFn fn = OperationUtil.getFn(func);
      operationCall.setContext(new Context(fn));
    }

    @Override
    public void start(FlowProcess flowProcess, AggregatorCall<Context> aggregatorCall) {
      aggregatorCall.getContext().reset();
    }

    @Override
    public void aggregate(FlowProcess flowProcess, AggregatorCall<Context> aggregatorCall) {
      Context context = aggregatorCall.getContext();
      TupleEntry args = aggregatorCall.getArguments();
      Object val = args.getObject(getFields(argField));
      if (!OperationUtil.SENTINEL_VALUE.equals(val)) {
        Object newAcc = COMPUTE_PARTIAL_FN.invoke(context.fn, OperationUtil.deserialize(val), context.acc);
        context.acc = newAcc;
        context.allSentinel = false;
      }
    }

    @Override
    public void complete(FlowProcess flowProcess, AggregatorCall<Context> aggregatorCall) {
      Context context = aggregatorCall.getContext();
      if (!context.allSentinel) {
        aggregatorCall.getOutputCollector().add(new Tuple(OperationUtil.serialize(context.acc)));
      }
    }
  }

  private static Fields getFields(String fieldName) {
    return new Fields("agg_result" + fieldName);
  }

  private PigPenAggregateBy(String outputField, String init, String func) {
    super(new Fields(outputField), new Partial(init, func, outputField), new Final(init, func, outputField));
  }

  public static AggregateBy buildAssembly(String name, Pipe[] pipes, Fields keyField, List<String> outputFields, List<String> inits, List<String> funcs) {
    AggregateBy[] assemblies = new AggregateBy[funcs.size()];
    for (int i = 0; i < funcs.size(); i++) {
      assemblies[i] = new PigPenAggregateBy(outputFields.get(i), inits.get(i), funcs.get(i));
    }
    return new AggregateBy(name, pipes, keyField, assemblies);
  }
}
