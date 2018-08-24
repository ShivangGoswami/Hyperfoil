package io.sailrocket.core.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import io.sailrocket.api.HttpClientPool;
import io.sailrocket.api.RequestQueue;
import io.sailrocket.api.Scenario;
import io.sailrocket.api.Sequence;
import io.sailrocket.api.SequenceInstance;
import io.sailrocket.api.Session;
import io.sailrocket.api.Statistics;
import io.sailrocket.api.ValidatorResults;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

class SessionImpl implements Session, Runnable {
   private static final Logger log = LoggerFactory.getLogger(SessionImpl.class);
   private static final boolean trace = log.isTraceEnabled();

   private final HttpClientPool httpClientPool;

   // Note: HashMap.get() is allocation-free, so we can use it for direct lookups. Replacing put() is also
   // allocation-free, so vars are OK to write as long as we have them declared.
   private final Map<Object, Var> vars = new HashMap<>();
   private final Map<ResourceKey, Resource> resources = new HashMap<>();
   private final List<Var> allVars = new ArrayList<>();
   private final RequestQueueImpl requestQueue;
   private final Pool<SequenceInstance> sequencePool;
   private final SequenceInstance[] runningSequences;
   private int lastRunningSequence = -1;
   private SequenceInstance currentSequence;
   private final BooleanSupplier termination;

   private final ValidatorResults validatorResults = new ValidatorResults();
   private final Statistics[] statistics;

   private Scenario scenario;

   public SessionImpl(HttpClientPool httpClientPool, int maxConcurrency, int maxScheduledSequences, BooleanSupplier termination, Scenario scenario) {
      this.httpClientPool = httpClientPool;
      this.requestQueue = new RequestQueueImpl(maxConcurrency);
      this.sequencePool = new Pool<>(maxConcurrency, SequenceInstance::new);
      this.runningSequences = new SequenceInstance[maxScheduledSequences];
      this.termination = termination;
      this.scenario = scenario;

      Sequence[] sequences = scenario.sequences();
      statistics = new Statistics[sequences.length];
      for (int i = 0; i < sequences.length; i++) {
         Sequence sequence = sequences[i];
         sequence.reserve(this);
         statistics[i] = new Statistics();
      }
      for (String var : scenario.objectVars()) {
         declare(var);
      }
      for (String var : scenario.intVars()) {
         declareInt(var);
      }
      for (Sequence sequence : scenario.initialSequences()) {
         sequence.instantiate(this, 0);
      }
   }

   @Override
   public HttpClientPool httpClientPool() {
      return httpClientPool;
   }

   void registerVar(Var var) {
      allVars.add(var);
   }

   @Override
   public Session declare(Object key) {
      ObjectVar var = new ObjectVar(this);
      vars.put(key, var);
      return this;
   }

   @Override
   public Object getObject(Object key) {
      return ((ObjectVar) requireSet(key, vars.get(key))).get();
   }

   @Override
   public Session setObject(Object key, Object value) {
      if (trace) {
         log.trace("{} <- {}", key, value);
      }
      ObjectVar wrapper = (ObjectVar) vars.get(key);
      wrapper.value = value;
      wrapper.set = true;
      return this;
   }

   @Override
   public Session declareInt(Object key) {
      IntVar var = new IntVar(this);
      vars.put(key, var);
      return this;
   }

   @Override
   public int getInt(Object key) {
      IntVar var = (IntVar) vars.get(key);
      if (!var.isSet()) {
         throw new IllegalStateException("Variable " + key + " was not set yet!");
      }
      return var.get();
   }

   @Override
   public Session setInt(Object key, int value) {
      if (trace) {
         log.trace("{} <- {}", key, value);
      }
      ((IntVar) vars.get(key)).set(value);
      return this;
   }

   @Override
   public Session addToInt(Object key, int delta) {
      IntVar wrapper = (IntVar) vars.get(key);
      if (!wrapper.isSet()) {
         throw new IllegalStateException("Variable " + key + " was not set yet!");
      }
      log.trace("{} <- {}", key, wrapper.get() + delta);
      wrapper.set(wrapper.get() + delta);
      return this;
   }

   @Override
   public boolean isSet(Object key) {
      return vars.get(key).isSet();
   }

   @Override
   public Object activate(Object key) {
      ObjectVar var = (ObjectVar) vars.get(key);
      var.set = true;
      return var.get();
   }

   @Override
   public void deactivate(Object key) {
      vars.get(key).unset();
   }

   @Override
   public <R extends Resource> void declareResource(ResourceKey<R> key, R resource) {
      resources.put(key, resource);
   }

   @Override
   public <R extends Resource> R getResource(ResourceKey<R> key) {
      return (R) resources.get(key);
   }

   private <W extends Var> W requireSet(Object key, W wrapper) {
      if (!wrapper.isSet()) {
         throw new IllegalStateException("Variable " + key + " was not set yet!");
      }
      return wrapper;
   }

   public void run() {
      for (;;) {
         while (lastRunningSequence >= 0) {
            boolean progressed = false;
            for (int i = 0; i <= lastRunningSequence; ++i) {
               currentSequence(runningSequences[i]);
               if (runningSequences[i].progress(this)) {
                  progressed = true;
                  if (runningSequences[i].isCompleted()) {
                     sequencePool.release(runningSequences[i]);
                     if (i == lastRunningSequence) {
                        runningSequences[i] = null;
                     } else {
                        runningSequences[i] = runningSequences[lastRunningSequence];
                        runningSequences[lastRunningSequence] = null;
                     }
                     --lastRunningSequence;
                  }
               }
               currentSequence(null);
            }
            if (!progressed) {
               return;
            }
         }
         if (termination.getAsBoolean()) {
            if (trace) {
               log.trace("Termination has fired.");
            }
            return;
         }
         reset();
         if (trace) {
            log.trace("Commencing new execution of the scenario.");
         }
         for (Sequence sequence : scenario.initialSequences()) {
            sequence.instantiate(this, 0);
         }
      }
   }

   @Override
   public void currentSequence(SequenceInstance current) {
      log.trace("Changing sequence {} -> {}", currentSequence, current);
      assert current == null || currentSequence == null;
      currentSequence = current;
   }

   public SequenceInstance currentSequence() {
      return currentSequence;
   }

   @Override
   public void proceed() {
      httpClientPool.submit(this);
   }

   @Override
   public ValidatorResults validatorResults() {
      return validatorResults;
   }

   @Override
   public Statistics statistics(int sequenceId) {
      return statistics[sequenceId];
   }

   @Override
   public Statistics[] statistics() {
      return statistics;
   }

   @Override
   public void reset() {
      sequencePool.checkFull();
      for (int i = 0; i < allVars.size(); ++i) {
         allVars.get(i).unset();
      }
      // TODO should we reset stats here?
   }

   @Override
   public RequestQueue requestQueue() {
      return requestQueue;
   }

   public SequenceInstance acquireSequence() {
      return sequencePool.acquire();
   }

   public void enableSequence(SequenceInstance instance) {
      lastRunningSequence++;
      assert lastRunningSequence < runningSequences.length && runningSequences[lastRunningSequence] == null;
      runningSequences[lastRunningSequence] = instance;
   }
}
