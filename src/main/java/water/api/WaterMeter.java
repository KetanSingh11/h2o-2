package water.api;

import dontweave.gson.*;
import water.*;
import water.util.LinuxProcFileReader;
import water.util.Log;

/**
 * Redirect to water meter page.
 */
public class WaterMeter extends HTMLOnlyRequest {
  protected String build(Response response) {
    return "<meta http-equiv=\"refresh\" content=\"0; url=watermeter/index.html\">";
  }

  public static class WaterMeterCpuTicks extends JSONOnlyRequest {
    Int node_idx = new Int("node_idx", -1);

    @Override
    public RequestServer.API_VERSION[] supportedVersions() { return SUPPORTS_ONLY_V2; }

    /**
     * Iterates over fields and their annotations, and creates argument handlers.
     */
    @Override protected void registered(RequestServer.API_VERSION version) {
      super.registered(version);
    }

    private static class GetTicksTask extends DTask<GetTicksTask> {
      private long[][] _cpuTicks;

      public GetTicksTask() {
        _cpuTicks = null;
      }

      @Override public void compute2() {
        LinuxProcFileReader lpfr = new LinuxProcFileReader();
        lpfr.read();
        if (lpfr.valid()) {
          _cpuTicks = lpfr.getCpuTicks();
        }
        else {
          _cpuTicks = new long[0][0];
        }

        tryComplete();
      }

      @Override public byte priority() {
        return H2O.MIN_HI_PRIORITY;
      }
    }

    @Override protected Response serve() {
      if ((node_idx.value() < 0) || (node_idx.value() >= H2O.CLOUD.size())) {
        throw new IllegalArgumentException("Illegal node_idx for this H2O cluster (must be from 0 to " + H2O.CLOUD.size() + ")");
      }

      H2ONode node = H2O.CLOUD._memary[node_idx.value()];
      GetTicksTask ppt = new GetTicksTask(); //same payload for all nodes
      Log.trace("GetTicksTask starting to node " + node_idx.value() + "...");
      new RPC<GetTicksTask>(node, ppt).call().get(); //blocking send
      Log.trace("GetTicksTask completed to node " + node_idx.value());
      long[][] cpuTicks = ppt._cpuTicks;

      JsonArray j = new JsonArray();
      for (long[] arr : cpuTicks) {
        JsonArray j2 = new JsonArray();
        j2.add(new JsonPrimitive(arr[0]));
        j2.add(new JsonPrimitive(arr[1]));
        j2.add(new JsonPrimitive(arr[2]));
        j2.add(new JsonPrimitive(arr[3]));
        j.add(j2);
      }
      JsonObject o = new JsonObject();
      o.add("cpuTicks", j);
      return Response.done(o);
    }
  }
}
