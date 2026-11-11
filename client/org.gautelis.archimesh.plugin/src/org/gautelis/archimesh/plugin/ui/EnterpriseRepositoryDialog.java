package org.gautelis.archimesh.plugin.ui;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.graphics.Point;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Network work runs off SWT. Accepted operations reach EMF exclusively through normal collaboration apply. */
public final class EnterpriseRepositoryDialog extends TitleAreaDialog {
    private final ArchimeshSessionManager session;
    private final String modelId;
    private final String serverUrl,userId;
    private final Object attachedModel;
    private final EnterpriseRepositoryClient client;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"archimesh-enterprise-catalog"); t.setDaemon(true); return t; });
    private Combo target,type,existing;
    private Text query,details;
    private org.eclipse.swt.widgets.List results;
    private Button search,previous,next,previewButton;
    private Label pageLabel;
    private java.util.List<EnterpriseRepositoryClient.Target> targets=java.util.List.of();
    private java.util.List<EnterpriseRepositoryClient.Card> cards=java.util.List.of();
    private EnterpriseRepositoryClient.Preview preview;
    private EnterpriseRepositoryClient.Request pending;
    private boolean busy;
    private int page=1;

    public EnterpriseRepositoryDialog(Shell shell,ArchimeshSessionManager session) throws java.io.IOException {
        super(shell); this.session=session; modelId=session.getCurrentModelId(); attachedModel=session.getAttachedModel();
        serverUrl=session.getCurrentWsBaseUrl(); userId=session.getUserId();
        client=new EnterpriseRepositoryClient(session.getCurrentWsBaseUrl(),modelId,session.getAuthToken(),session.getUserId());
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }
    @Override public void create() {
        super.create(); setTitle("Enterprise Repository");
        setMessage("Search the approved Turbo EA catalog, review the import, then create, reuse or link a model element.");
        run(client::targets,loaded -> {
            targets=loaded; target.removeAll(); loaded.forEach(t -> target.add(t.key()));
            if (loaded.isEmpty()) setErrorMessage("No approved catalog is available for this model and user.");
            else { target.select(0); updateTypes(); }
        });
    }
    @Override protected Control createDialogArea(Composite parent) {
        Composite area=(Composite)super.createDialogArea(parent);
        Composite content=new Composite(area,SWT.NONE); content.setLayout(new GridLayout(2,false)); content.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
        label(content,"Repository"); target=new Combo(content,SWT.READ_ONLY); target.setLayoutData(fill()); target.addListener(SWT.Selection,e -> updateTypes());
        label(content,"Object type"); type=new Combo(content,SWT.READ_ONLY); type.setLayoutData(fill()); type.addListener(SWT.Selection,e -> clearPreview());
        label(content,"Search"); query=new Text(content,SWT.BORDER); query.setLayoutData(fill()); query.setTextLimit(200);
        Composite navigation=new Composite(content,SWT.NONE); navigation.setLayout(new GridLayout(4,false)); navigation.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,2,1));
        search=button(navigation,"Search",() -> search(1)); previous=button(navigation,"Previous",() -> search(page-1)); next=button(navigation,"Next",() -> search(page+1));
        pageLabel=new Label(navigation,SWT.NONE); pageLabel.setLayoutData(fill());
        results=new org.eclipse.swt.widgets.List(content,SWT.BORDER|SWT.V_SCROLL); results.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true,2,1));
        results.addListener(SWT.Selection,e -> clearPreview());
        previewButton=button(content,"Preview selected object",this::loadPreview); previewButton.setLayoutData(new GridData(SWT.LEFT,SWT.CENTER,false,false,2,1));
        details=new Text(content,SWT.BORDER|SWT.MULTI|SWT.WRAP|SWT.V_SCROLL|SWT.READ_ONLY); var detailLayout=new GridData(SWT.FILL,SWT.FILL,true,true,2,1); detailLayout.heightHint=160; details.setLayoutData(detailLayout);
        label(content,"Use in model"); existing=new Combo(content,SWT.READ_ONLY); existing.setLayoutData(fill());
        return area;
    }
    @Override protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent,IDialogConstants.OK_ID,"Use in model",true); createButton(parent,IDialogConstants.CANCEL_ID,"Close",false); updateButtons();
    }
    private void updateTypes() {
        type.removeAll(); int index=target.getSelectionIndex();
        if (index>=0 && index<targets.size()) targets.get(index).types().forEach(type::add);
        if (type.getItemCount()>0) type.select(0); page=1; next.setData("available",false); pageLabel.setText("");
        results.removeAll(); cards=java.util.List.of(); clearPreview();
    }
    private void clearPreview() { preview=null; pending=null; if (details!=null) details.setText(""); if (existing!=null) existing.removeAll(); updateButtons(); }
    private void search(int requestedPage) {
        if (requestedPage<1 || target.getSelectionIndex()<0 || type.getSelectionIndex()<0) return;
        String selectedTarget=target.getText(),selectedType=type.getText(),searchText=query.getText();
        clearPreview(); run(() -> client.search(selectedTarget,selectedType,searchText,requestedPage),loaded -> {
            page=loaded.page(); cards=loaded.items(); results.removeAll();
            cards.forEach(card -> results.add(card.name()+ (card.elementId()!=null ? " (already linked)" : "")));
            pageLabel.setText("Page "+page+" · "+loaded.total()+" objects");
            next.setData("available",(long)page*loaded.pageSize()<loaded.total()); updateButtons();
        });
    }
    private void loadPreview() {
        int index=results.getSelectionIndex(); if (index<0 || index>=cards.size()) return;
        String selectedTarget=target.getText(),cardId=cards.get(index).id(); clearPreview();
        run(() -> client.preview(selectedTarget,cardId),loaded -> {
            preview=loaded; details.setText(loaded.name()+"\n"+(loaded.description()==null ? "" : loaded.description()+"\n")+loaded.detail());
            existing.removeAll(); existing.add(loaded.elementId()==null ? "Create a new ArchiMate element" : "Reuse the existing linked element");
            if (loaded.elementId()==null) loaded.compatible().forEach(option -> existing.add("Link: "+option.name()+" ("+option.id()+")"));
            existing.select(0); updateButtons();
        });
    }
    @Override protected void okPressed() {
        if (preview==null || busy) return;
        if (!session.isConnected() || session.isCurrentReferenceReadOnly() || !modelId.equals(session.getCurrentModelId())
                || !Objects.equals(serverUrl,session.getCurrentWsBaseUrl()) || !Objects.equals(userId,session.getUserId()) || attachedModel!=session.getAttachedModel()) {
            setErrorMessage("The connected model has changed. Close this dialog and reopen it for the current writable model."); return;
        }
        if (pending==null) {
            int selection=existing.getSelectionIndex();
            String element=selection>0 ? preview.compatible().get(selection-1).id() : null;
            pending=new EnterpriseRepositoryClient.Request(UUID.randomUUID().toString(),target.getText(),preview.cardId(),selection>0 ? "LINK" : "CREATE",element,preview.digest());
        }
        var request=pending;
        run(() -> client.importObject(request),accepted -> {
            pending=null;
            MessageDialog.openInformation(getShell(),"Object accepted", "The model element is committed. It will appear through normal synchronization.\n\nUse the model tree to place it in any ArchiMate view.");
            close();
        });
    }
    private <T> void run(Callable<T> action,Consumer<T> onSuccess) {
        if (busy) return; busy=true; setErrorMessage(null); updateButtons();
        Display display=getShell().getDisplay();
        worker.submit(() -> {
            T value=null; Exception failure=null;
            try { value=action.call(); } catch (Exception error) { failure=error; }
            T result=value; Exception error=failure;
            if (display.isDisposed()) return;
            display.asyncExec(() -> {
                if (getShell()==null || getShell().isDisposed()) return;
                busy=false;
                if (error==null) onSuccess.accept(result);
                else {
                    setErrorMessage(error.getMessage()==null ? "The repository request failed." : error.getMessage());
                    if (error instanceof EnterpriseRepositoryClient.RequestFailure denied && denied.status()<500) pending=null;
                }
                updateButtons();
            });
        });
    }
    private void updateButtons() {
        if (search==null) return;
        boolean editable=!busy && pending==null;
        target.setEnabled(editable); type.setEnabled(editable); query.setEnabled(editable); results.setEnabled(editable); existing.setEnabled(editable);
        search.setEnabled(editable && type.getSelectionIndex()>=0); previewButton.setEnabled(editable && results.getSelectionIndex()>=0);
        previous.setEnabled(editable && page>1); next.setEnabled(editable && Boolean.TRUE.equals(next.getData("available")));
        Button use=getButton(IDialogConstants.OK_ID);
        if (use!=null) { int index=target.getSelectionIndex(); use.setEnabled(!busy && preview!=null && index>=0 && targets.get(index).canImport()); use.setText(pending==null ? "Use in model" : "Retry import"); }
    }
    @Override public boolean close() { worker.shutdownNow(); client.close(); return super.close(); }
    @Override protected Point getInitialSize() { return new Point(760,780); }
    private static void label(Composite parent,String text) { new Label(parent,SWT.NONE).setText(text); }
    private static GridData fill() { return new GridData(SWT.FILL,SWT.CENTER,true,false); }
    private static Button button(Composite parent,String text,Runnable action) { Button button=new Button(parent,SWT.PUSH); button.setText(text); button.addListener(SWT.Selection,event -> action.run()); return button; }
}
