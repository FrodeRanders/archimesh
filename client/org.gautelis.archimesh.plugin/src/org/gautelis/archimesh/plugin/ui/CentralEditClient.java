package org.gautelis.archimesh.plugin.ui;

import org.gautelis.archimesh.plugin.util.SimpleJson;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import static org.gautelis.archimesh.plugin.ui.EnterpriseRepositoryClient.*;

/** Uses only the authenticated ArchiMesh origin. Proposed complex values remain exact text, never doubles. */
public final class CentralEditClient implements AutoCloseable {
    public record Binding(String elementId,String name) { }
    public record Bindings(List<Binding> items,String nextCursor) { }
    public record Field(String name,String valueType,String authority,String mode,String current,String local,String baseline) { }
    public record Context(String elementId,String canonicalId,String resourceId,String type,List<Field> fields) { }
    public record Preview(String digest,String mode,String detail) { }
    public record Request(String requestId,String target,String elementId,String field,String value,String digest) { }
    public record Job(String id,String state,String code,String detail) { }
    private final EnterpriseRepositoryClient transport;
    public CentralEditClient(String url,String model,String token,String user) throws IOException { transport=new EnterpriseRepositoryClient(url,model,token,user,"central-edits"); }
    public List<String> targets() throws IOException,InterruptedException {
        var values=new ArrayList<String>(); var matcher=Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(transport.send("GET","/targets",null));
        while (matcher.find()) { String key=SimpleJson.decodeString(matcher.group(1)); var scope=transport.send("GET","/"+encode(key)+"/capabilities",null); if (Boolean.TRUE.equals(SimpleJson.readBooleanField(scope,"canRequest"))) values.add(key); } return List.copyOf(values);
    }
    public Bindings bindings(String target,String after) throws IOException,InterruptedException {
        String response=transport.send("GET","/"+encode(target)+"/bindings?after="+encode(after),null); var result=new ArrayList<Binding>();
        for (String item:SimpleJson.readArrayObjectElements(response,"items")) result.add(new Binding(required(item,"elementId"),required(item,"name")));
        return new Bindings(List.copyOf(result),SimpleJson.readStringField(response,"nextCursor"));
    }
    public Context context(String target,String element) throws IOException,InterruptedException {
        String response=transport.send("GET","/"+encode(target)+"/context/"+encode(element),null); var fields=new ArrayList<Field>();
        for (String item:SimpleJson.readArrayObjectElements(response,"fields")) fields.add(new Field(required(item,"field"),required(item,"valueType"),required(item,"authority"),required(item,"mode"),display(item,"current"),display(item,"local"),display(item,"baseline")));
        return new Context(required(response,"elementId"),required(response,"canonicalId"),required(response,"resourceId"),required(response,"type"),List.copyOf(fields));
    }
    public Preview preview(String target,String element,String field,String value) throws IOException,InterruptedException {
        String response=transport.send("POST","/"+encode(target)+"/preview","{\"ref\":\"HEAD\",\"elementId\":"+quote(element)+",\"proposed\":{"+quote(field)+":"+quote(value)+"}}");
        String mode=required(response,"mode"); var detail=new StringBuilder("Action: ").append(mode.equals("PROPOSAL") ? "Request approval; no central write until approved" : "Explicit central write, followed by verified model synchronization");
        String context=SimpleJson.readRawField(response,"context");
        detail.append("\nCanonical identity: ").append(required(context,"canonicalId")).append("\nCentral card: ").append(required(context,"resourceId"));
        for (String item:SimpleJson.readArrayObjectElements(context,"fields")) detail.append("\n\n").append(required(item,"field")).append(" [").append(required(item,"authority")).append("]\nCurrent central: ").append(display(item,"current")).append("\nCurrent local: ").append(display(item,"local")).append("\nLast baseline: ").append(display(item,"baseline"));
        detail.append("\n\nProposed value: ").append(value==null ? "(remove/clear)" : value);
        return new Preview(required(response,"digest"),mode,detail.toString());
    }
    public Job submit(Request request) throws IOException,InterruptedException {
        String body="{\"requestId\":"+quote(request.requestId())+",\"ref\":\"HEAD\",\"elementId\":"+quote(request.elementId())+",\"proposed\":{"+quote(request.field())+":"+quote(request.value())+"},\"previewDigest\":"+quote(request.digest())+"}";
        return job(transport.send("POST","/"+encode(request.target())+"/requests",body));
    }
    public Job get(String target,String id) throws IOException,InterruptedException { return job(transport.send("GET","/"+encode(target)+"/requests/"+encode(id),null)); }
    public List<Job> recent(String target) throws IOException,InterruptedException {
        var jobs=new ArrayList<Job>(); for (String item:SimpleJson.readArrayObjectElements(transport.send("GET","/"+encode(target)+"/requests",null),"jobs")) jobs.add(job(item)); return List.copyOf(jobs);
    }
    private Job job(String text) throws IOException {
        String state=required(text,"state"),code=SimpleJson.readStringField(text,"code");
        String detail="State: "+state+(code==null ? "" : "\nDiagnostic: "+code)+"\nRemote result verified: "+Boolean.TRUE.equals(SimpleJson.readBooleanField(text,"remoteVerified"))
                +"\nModel occurrences synchronized: "+SimpleJson.readLongField(text,"synced")+"\nPending: "+SimpleJson.readLongField(text,"pending")+"\nConflicts: "+SimpleJson.readLongField(text,"conflicts")
                +"\nThis model: "+Objects.toString(SimpleJson.readStringField(text,"sourceState"),"pending")+"\n\nRequest: "+required(text,"id");
        return new Job(required(text,"id"),state,code,detail);
    }
    private String display(String json,String field) { String raw=SimpleJson.readRawField(json,field); if (raw==null || raw.equals("null")) return "(not set)"; String text=SimpleJson.readStringField(json,field); return text==null ? raw : text; }
    @Override public void close() { transport.close(); }
}
