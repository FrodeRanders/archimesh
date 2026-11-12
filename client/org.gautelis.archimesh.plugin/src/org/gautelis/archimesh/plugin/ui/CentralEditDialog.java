package org.gautelis.archimesh.plugin.ui;

import org.eclipse.jface.dialogs.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Explicit central command UI. EMF is changed only by accepted normal collaboration messages. */
public final class CentralEditDialog extends TitleAreaDialog {
    private final ArchimeshSessionManager session;
    private final String model,server,user;
    private final Object attached;
    private final CentralEditClient client;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"archimesh-central-edit"); t.setDaemon(true); return t; });
    private Combo target,element,field,recent;
    private Text value,details;
    private Button previewButton,more,clear,status;
    private Label hint;
    private java.util.List<CentralEditClient.Binding> bindings=new ArrayList<>();
    private java.util.List<CentralEditClient.Job> jobs=List.of();
    private CentralEditClient.Context context;
    private CentralEditClient.Preview preview;
    private CentralEditClient.Request pending;
    private String cursor,lastJob;
    private boolean busy;
    public CentralEditDialog(Shell shell,ArchimeshSessionManager session) throws java.io.IOException {
        super(shell); this.session=session; model=session.getCurrentModelId(); server=session.getCurrentWsBaseUrl(); user=session.getUserId(); attached=session.getAttachedModel();
        client=new CentralEditClient(server,model,session.getAuthToken(),user); setShellStyle(getShellStyle()|SWT.RESIZE);
    }
    @Override public void create() {
        super.create(); setTitle("Enterprise Repository — Propose/change central data"); setMessage("Review the central value and ownership before requesting a change. Approvals are managed in the ArchiMesh admin console.");
        run(client::targets,loaded -> { target.removeAll(); loaded.forEach(target::add); if (loaded.isEmpty()) setErrorMessage("No central edit scope is enabled for this model and user."); else { target.select(0); loadTarget(); } });
    }
    @Override protected Control createDialogArea(Composite parent) {
        Composite area=(Composite)super.createDialogArea(parent); Composite body=new Composite(area,SWT.NONE); body.setLayout(new GridLayout(2,false)); body.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
        label(body,"Repository"); target=new Combo(body,SWT.READ_ONLY); target.setLayoutData(fill()); target.addListener(SWT.Selection,e -> loadTarget());
        label(body,"Linked element"); element=new Combo(body,SWT.READ_ONLY); element.setLayoutData(fill()); element.addListener(SWT.Selection,e -> loadContext());
        more=button(body,"More linked elements",() -> loadBindings(true)); more.setLayoutData(new GridData(SWT.LEFT,SWT.CENTER,false,false,2,1));
        label(body,"Central field"); field=new Combo(body,SWT.READ_ONLY); field.setLayoutData(fill()); field.addListener(SWT.Selection,e -> selectField());
        hint=new Label(body,SWT.WRAP); hint.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,2,1));
        label(body,"Proposed value"); value=new Text(body,SWT.BORDER|SWT.MULTI|SWT.WRAP|SWT.V_SCROLL); value.setTextLimit(16384); var size=new GridData(SWT.FILL,SWT.FILL,true,false); size.heightHint=80; value.setLayoutData(size); value.addModifyListener(e -> invalidatePreview());
        clear=new Button(body,SWT.CHECK); clear.setText("Remove/clear this value"); clear.setLayoutData(new GridData(SWT.LEFT,SWT.CENTER,false,false,2,1)); clear.addListener(SWT.Selection,e -> { invalidatePreview(); updateButtons(); });
        previewButton=button(body,"Preview central change",this::loadPreview); previewButton.setLayoutData(new GridData(SWT.LEFT,SWT.CENTER,false,false,2,1));
        details=new Text(body,SWT.BORDER|SWT.READ_ONLY|SWT.MULTI|SWT.WRAP|SWT.V_SCROLL); var detail=new GridData(SWT.FILL,SWT.FILL,true,true,2,1); detail.heightHint=220; details.setLayoutData(detail);
        label(body,"Recent requests"); recent=new Combo(body,SWT.READ_ONLY); recent.setLayoutData(fill()); recent.addListener(SWT.Selection,e -> { int index=recent.getSelectionIndex(); if (index>=0) { lastJob=jobs.get(index).id(); details.setText(jobs.get(index).detail()); updateButtons(); } });
        status=button(body,"Refresh request status",this::loadStatus); status.setLayoutData(new GridData(SWT.LEFT,SWT.CENTER,false,false,2,1));
        return area;
    }
    @Override protected void createButtonsForButtonBar(Composite parent) { createButton(parent,IDialogConstants.OK_ID,"Submit request",true); createButton(parent,IDialogConstants.CANCEL_ID,"Close",false); updateButtons(); }
    private void loadTarget() { context=null; preview=null; pending=null; lastJob=null; field.removeAll(); element.removeAll(); recent.removeAll(); bindings=new ArrayList<>(); jobs=List.of(); cursor=null; details.setText(""); loadBindings(false); }
    private void loadBindings(boolean append) {
        if (target.getSelectionIndex()<0) return; String key=target.getText(),after=append ? cursor : "";
        run(() -> client.bindings(key,after),page -> { if (!append) { bindings.clear(); element.removeAll(); } page.items().forEach(binding -> { bindings.add(binding); element.add(binding.name()+" ("+binding.elementId()+")"); }); cursor=page.nextCursor(); if (!bindings.isEmpty() && element.getSelectionIndex()<0) { element.select(0); loadContext(); } });
    }
    private void loadContext() {
        int index=element.getSelectionIndex(); if (index<0) return; String key=target.getText(),id=bindings.get(index).elementId(); context=null; invalidatePreview();
        run(() -> client.context(key,id),loaded -> { context=loaded; field.removeAll(); loaded.fields().forEach(f -> field.add(f.name()+" — "+f.mode())); if (field.getItemCount()>0) { field.select(0); selectField(); } loadRecent(); });
    }
    private void selectField() {
        invalidatePreview(); int index=field.getSelectionIndex(); if (context==null || index<0) return; var selected=context.fields().get(index);
        clear.setSelection(false); clear.setEnabled(!selected.name().equals("name")); value.setText("");
        hint.setText(selected.authority()+" · "+selected.mode()+" · "+(selected.valueType().equals("STRING") ? "Enter text" : "Enter a JSON "+selected.valueType().toLowerCase()));
        details.setText("Canonical identity: "+context.canonicalId()+"\nCentral card: "+context.resourceId()+"\nType: "+context.type()+"\n\nCurrent central: "+selected.current()+"\nCurrent local: "+selected.local()+"\nLast baseline: "+selected.baseline()); updateButtons();
    }
    private void loadPreview() {
        if (!validSession() || context==null || field.getSelectionIndex()<0) return; String key=target.getText(),id=context.elementId(),name=context.fields().get(field.getSelectionIndex()).name(),proposed=clear.getSelection() ? null : value.getText();
        run(() -> client.preview(key,id,name,proposed),loaded -> { preview=loaded; details.setText(loaded.detail()); });
    }
    @Override protected void okPressed() {
        if (!validSession() || preview==null || busy) return;
        if (pending==null) {
            if (!MessageDialog.openConfirm(getShell(),"Confirm central change",preview.detail()+"\n\nSubmit this explicit request? Model changes arrive after verified synchronization.")) return;
            pending=new CentralEditClient.Request(UUID.randomUUID().toString(),target.getText(),context.elementId(),context.fields().get(field.getSelectionIndex()).name(),clear.getSelection() ? null : value.getText(),preview.digest());
        }
        var request=pending; run(() -> client.submit(request),job -> { pending=null; preview=null; lastJob=job.id(); details.setText(job.detail()); setMessage(job.state().equals("WAITING_APPROVAL") ? "Proposal saved. A scoped model administrator must approve it in the admin console." : "Request queued. Remote verification and model delivery are separate steps; refresh status to inspect the result."); loadRecent(); });
    }
    private void loadRecent() { if (target.getSelectionIndex()<0) return; String key=target.getText(); run(() -> client.recent(key),loaded -> { jobs=loaded; recent.removeAll(); for (var job:loaded) recent.add(job.state()+" · "+job.id()); }); }
    private void loadStatus() { if (lastJob==null) return; String key=target.getText(),id=lastJob; run(() -> client.get(key,id),job -> details.setText(job.detail())); }
    private void invalidatePreview() { preview=null; if (pending==null) updateButtons(); }
    private boolean validSession() { boolean valid=session.isConnected() && !session.isCurrentReferenceReadOnly() && model.equals(session.getCurrentModelId()) && Objects.equals(server,session.getCurrentWsBaseUrl()) && Objects.equals(user,session.getUserId()) && attached==session.getAttachedModel(); if (!valid) { setErrorMessage("The connected model or identity changed. Reopen this dialog for the current writable model."); preview=null; } return valid; }
    private <T> void run(Callable<T> action,Consumer<T> success) {
        if (busy || !validSession()) return; busy=true; setErrorMessage(null); updateButtons(); var display=getShell().getDisplay();
        worker.submit(() -> { T result=null; Exception failure=null; try { result=action.call(); } catch (Exception error) { failure=error; } T loaded=result; Exception error=failure;
            if (display.isDisposed()) return; display.asyncExec(() -> { if (getShell()==null || getShell().isDisposed()) return; busy=false; if (!validSession()) { updateButtons(); return; } if (error==null) success.accept(loaded); else { setErrorMessage(error.getMessage()); if (error instanceof EnterpriseRepositoryClient.RequestFailure denied && denied.status()<500) { pending=null; preview=null; } } updateButtons(); }); });
    }
    private void updateButtons() {
        if (target==null) return; boolean editable=!busy && pending==null; target.setEnabled(editable); element.setEnabled(editable); field.setEnabled(editable); value.setEnabled(editable && !clear.getSelection()); clear.setEnabled(editable && context!=null && field.getSelectionIndex()>=0 && !context.fields().get(field.getSelectionIndex()).name().equals("name"));
        previewButton.setEnabled(editable && context!=null && field.getSelectionIndex()>=0); more.setEnabled(editable && cursor!=null); recent.setEnabled(editable); status.setEnabled(!busy && lastJob!=null);
        var submit=getButton(IDialogConstants.OK_ID); if (submit!=null) { submit.setEnabled(!busy && preview!=null); submit.setText(pending!=null ? "Retry same request" : preview!=null && preview.mode().equals("WRITE_THROUGH") ? "Apply central change" : "Submit proposal"); }
    }
    @Override public boolean close() { worker.shutdownNow(); client.close(); return super.close(); }
    @Override protected Point getInitialSize() { return new Point(820,850); }
    private static GridData fill() { return new GridData(SWT.FILL,SWT.CENTER,true,false); }
    private static void label(Composite parent,String text) { new Label(parent,SWT.NONE).setText(text); }
    private static Button button(Composite parent,String text,Runnable action) { var button=new Button(parent,SWT.PUSH); button.setText(text); button.addListener(SWT.Selection,e -> action.run()); return button; }
}
