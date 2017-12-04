// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.xdebugger.impl.frame;

import com.intellij.ide.CommonActionsManager;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.*;
import com.intellij.ui.border.CustomLineBorder;
import com.intellij.ui.components.panels.Wrapper;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.TransferToEDTQueue;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import com.intellij.xdebugger.impl.actions.XDebuggerActions;
import gnu.trove.TObjectIntHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

/**
 * @author nik
 */
public class XFramesView extends XDebugView {
  private static final Logger LOG = Logger.getInstance(XFramesView.class);

  private final JPanel myMainPanel;
  private final XDebuggerFramesList myFramesList;
  private final ComboBox<XExecutionStack> myThreadComboBox;
  private final JTextField myThreadFilterField;
  private final TObjectIntHashMap<XExecutionStack> myExecutionStacksWithSelection = new TObjectIntHashMap<>();
  private final List<XExecutionStack> myUnfilteredExecutionStacks = new ArrayList<>();
  private final Map<XExecutionStack, Optional<String>> myExecutionStackToStackTrace = new HashMap<>();
  private int computingStackTraces = 0;
  private XExecutionStack mySelectedStack;
  private int mySelectedFrameIndex;
  private Rectangle myVisibleRect;
  private boolean myListenersEnabled;
  private final Map<XExecutionStack, StackFramesListBuilder> myBuilders = new HashMap<>();
  private final ActionToolbarImpl myToolbar;
  private final Wrapper myThreadsPanel;
  private boolean myThreadsCalculated = false;
  private final TransferToEDTQueue<Runnable> myLaterInvocator = TransferToEDTQueue.createRunnableMerger("XFramesView later invocator");
  private boolean myRefresh = false;

  public XFramesView(@NotNull Project project) {
    myMainPanel = new JPanel(new BorderLayout());

    myFramesList = new XDebuggerFramesList(project);
    myFramesList.addListSelectionListener(new ListSelectionListener() {
      @Override
      public void valueChanged(ListSelectionEvent e) {
        if (myListenersEnabled && !e.getValueIsAdjusting() && mySelectedFrameIndex != myFramesList.getSelectedIndex()) {
          processFrameSelection(getSession(e), true);
        }
      }
    });
    myFramesList.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(final MouseEvent e) {
        if (myListenersEnabled) {
          int i = myFramesList.locationToIndex(e.getPoint());
          if (i != -1 && myFramesList.isSelectedIndex(i)) {
            processFrameSelection(getSession(e), true);
          }
        }
      }
    });

    myFramesList.addMouseListener(new PopupHandler() {
      @Override
      public void invokePopup(final Component comp, final int x, final int y) {
        ActionManager actionManager = ActionManager.getInstance();
        ActionGroup group = (ActionGroup)actionManager.getAction(XDebuggerActions.FRAMES_TREE_POPUP_GROUP);
        actionManager.createActionPopupMenu(ActionPlaces.UNKNOWN, group).getComponent().show(comp, x, y);
      }
    });

    myMainPanel.add(ScrollPaneFactory.createScrollPane(myFramesList), BorderLayout.CENTER);

    myThreadComboBox = new ComboBox<>();
    myThreadComboBox.setRenderer(new ThreadComboBoxRenderer(myThreadComboBox));
    myThreadComboBox.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(final ItemEvent e) {
        if (!myListenersEnabled) {
          return;
        }

        if (e.getStateChange() == ItemEvent.SELECTED) {
          Object item = e.getItem();
          if (item != mySelectedStack && item instanceof XExecutionStack) {
            XDebugSession session = getSession(e);
            if (session != null) {
              myRefresh = false;
              updateFrames((XExecutionStack)item, session, null);
            }
          }
        }
      }
    });
    myThreadComboBox.addPopupMenuListener(new PopupMenuListenerAdapter() {
      ThreadsBuilder myBuilder;

      @Override
      public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
        stopBuilder();
      }

      @Override
      public void popupMenuCanceled(PopupMenuEvent e) {
        stopBuilder();
      }

      private void stopBuilder() {
        if (myBuilder != null) {
          myBuilder.setObsolete();
          myBuilder = null;
        }
      }

      @Override
      public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
        XDebugSession session = getSession(e);
        XSuspendContext context = session == null ? null : session.getSuspendContext();
        if (context != null && !myThreadsCalculated) {
          myBuilder = new ThreadsBuilder();
          context.computeExecutionStacks(myBuilder);
        }
      }
    });
    new ComboboxSpeedSearch(myThreadComboBox) {
      @Override
      protected String getElementText(Object element) {
        return ((XExecutionStack)element).getDisplayName();
      }
    };
    myThreadComboBox.setMaximumSize(new Dimension(150, (int) myThreadComboBox.getMaximumSize().getHeight()));

    myThreadFilterField = new JTextField();
    myThreadFilterField.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        changedUpdate(e);
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        changedUpdate(e);
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        ApplicationManager.getApplication().invokeLater(() -> {
          // set items of myThreadComboBox to the filtered ones
          myThreadComboBox.removeAllItems();
          myUnfilteredExecutionStacks.stream().filter(s -> matchesFilter(s)).forEach(s -> myThreadComboBox.addItem(s));
        });
      }
    });

    myToolbar = createToolbar();
    myThreadsPanel = new Wrapper();
    myThreadsPanel.setBorder(new CustomLineBorder(CaptionPanel.CNT_ACTIVE_BORDER_COLOR, 0, 0, 1, 0));
    myThreadsPanel.add(myToolbar.getComponent(), BorderLayout.EAST);
    myMainPanel.add(myThreadsPanel, BorderLayout.NORTH);
  }

  /** Builds the stack trace of an execution stack to allow to filter on it. */
  private class StackTraceBuilder implements XStackFrameContainerEx {
    private final XExecutionStack myStack;
    private final StringBuffer myStackTraceBuilder = new StringBuffer();

    public StackTraceBuilder(XExecutionStack stack) {
      this.myStack = stack;
    }

    @Override
    public void addStackFrames(@NotNull List<? extends XStackFrame> stackFrames, @Nullable XStackFrame toSelect, boolean last) {
      addStackFrames(stackFrames, last);
    }

    @Override
    public void addStackFrames(@NotNull List<? extends XStackFrame> stackFrames, boolean last) {
      for (XStackFrame frame : stackFrames) {
        myStackTraceBuilder.append(frame.toString()).append('\n');
      }

      if (last) {
        synchronized (myExecutionStackToStackTrace) {
          myExecutionStackToStackTrace.put(myStack, Optional.of(myStackTraceBuilder.toString()));
          computationDone();
        }

        // eventually add execution stack to combo box if filter is fulfilled
        ApplicationManager.getApplication().invokeLater(() -> {
          if (matchesFilter(myStack)) {
            boolean stackContained = false;
            for (int i = 0; i < myThreadComboBox.getItemCount() && !stackContained; i++) {
              final XExecutionStack stack = myThreadComboBox.getItemAt(i);
              if (stack == myStack) {
                stackContained = true;
              }
            }

            if (!stackContained) {
              myThreadComboBox.addItem(myStack);
            }
          }
        });
      }
    }

    @Override
    public void errorOccurred(@NotNull String errorMessage) {
      synchronized (myExecutionStackToStackTrace) {
        computationDone();
      }
    }

    private void computationDone() {
      computingStackTraces--;
      if (computingStackTraces == 0) {
        ApplicationManager.getApplication().invokeLater(() -> {
          myThreadFilterField.setBackground(JBColor.WHITE);
        });
      }
    }
  }

  private class ThreadsBuilder implements XSuspendContext.XExecutionStackContainer {
    private volatile boolean myObsolete;

    public ThreadsBuilder() {
      myThreadComboBox.addItem(null); // rendered as "Loading..."
    }

    @Override
    public void addExecutionStack(@NotNull List<? extends XExecutionStack> executionStacks, boolean last) {
      ArrayList<? extends XExecutionStack> copyStacks = new ArrayList<>(executionStacks); // to capture the current List elements
      ApplicationManager.getApplication().invokeLater(() -> {
        int initialCount = myThreadComboBox.getItemCount();
        if (last) {
          removeLoading();
          myThreadsCalculated = true;
        }
        addExecutionStacks(copyStacks);

        // reopen if popups height changed
        int newCount = myThreadComboBox.getItemCount();
        int maxComboboxRows = myThreadComboBox.getMaximumRowCount();
        if (newCount != initialCount && (initialCount < maxComboboxRows || newCount < maxComboboxRows)) {
          ComboPopup popup = myThreadComboBox.getPopup();
          if (popup != null && popup.isVisible()) {
            popup.hide();
            popup.show();
          }
        }
      });
    }

    @Override
    public void errorOccurred(@NotNull String errorMessage) {
      ApplicationManager.getApplication().invokeLater(this::removeLoading);
    }

    @Override
    public boolean isObsolete() {
      return myObsolete;
    }

    public void setObsolete() {
      if (!myObsolete) {
        myObsolete = true;
        removeLoading();
      }
    }

    void removeLoading() {
      myThreadComboBox.removeItem(null);
    }
  }

  private ActionToolbarImpl createToolbar() {
    final DefaultActionGroup framesGroup = new DefaultActionGroup();

    CommonActionsManager actionsManager = CommonActionsManager.getInstance();
    framesGroup.add(actionsManager.createPrevOccurenceAction(myFramesList));
    framesGroup.add(actionsManager.createNextOccurenceAction(myFramesList));

    framesGroup.addAll(ActionManager.getInstance().getAction(XDebuggerActions.FRAMES_TOP_TOOLBAR_GROUP));

    final ActionToolbarImpl toolbar =
      (ActionToolbarImpl)ActionManager.getInstance().createActionToolbar(ActionPlaces.DEBUGGER_TOOLBAR, framesGroup, true);
    toolbar.setReservePlaceAutoPopupIcon(false);
    toolbar.setAddSeparatorFirst(true);
    return toolbar;
  }

  private StackFramesListBuilder getOrCreateBuilder(XExecutionStack executionStack, XDebugSession session) {
    return myBuilders.computeIfAbsent(executionStack, k -> new StackFramesListBuilder(executionStack, session));
  }

  @Override
  public void processSessionEvent(@NotNull SessionEvent event, @NotNull XDebugSession session) {
    myRefresh = event == SessionEvent.SETTINGS_CHANGED;

    if (event == SessionEvent.BEFORE_RESUME) {
      return;
    }

    XExecutionStack currentExecutionStack = ((XDebugSessionImpl)session).getCurrentExecutionStack();
    XStackFrame currentStackFrame = session.getCurrentStackFrame();
    XSuspendContext suspendContext = session.getSuspendContext();

    if (event == SessionEvent.FRAME_CHANGED && Objects.equals(mySelectedStack, currentExecutionStack)) {
      ApplicationManager.getApplication().assertIsDispatchThread();
      if (currentStackFrame != null) {
        myFramesList.setSelectedValue(currentStackFrame, true);
        mySelectedFrameIndex = myFramesList.getSelectedIndex();
        myExecutionStacksWithSelection.put(mySelectedStack, mySelectedFrameIndex);
      }
      return;
    }

    myLaterInvocator.offer(() -> {
      if (event != SessionEvent.SETTINGS_CHANGED) {
        mySelectedFrameIndex = 0;
        mySelectedStack = null;
        myVisibleRect = null;
      }
      else {
        myVisibleRect = myFramesList.getVisibleRect();
      }

      myListenersEnabled = false;
      myBuilders.values().forEach(StackFramesListBuilder::dispose);
      myBuilders.clear();

      if (suspendContext == null) {
        requestClear();
        return;
      }

      if (event == SessionEvent.PAUSED) {
        // clear immediately
        cancelClear();
        clear();
      }

      XExecutionStack activeExecutionStack = mySelectedStack != null ? mySelectedStack : currentExecutionStack;
      addExecutionStacks(Collections.singletonList(activeExecutionStack));

      XExecutionStack[] executionStacks = suspendContext.getExecutionStacks();
      addExecutionStacks(Arrays.asList(executionStacks));

      myThreadComboBox.setSelectedItem(activeExecutionStack);
      myThreadsPanel.removeAll();
      myThreadsPanel.add(myToolbar.getComponent(), BorderLayout.EAST);
      final boolean invisible = executionStacks.length == 1 && StringUtil.isEmpty(executionStacks[0].getDisplayName());
      if (!invisible) {
        final JPanel threadComboBoxAndFilterField = new JPanel() {{
          setLayout(new GridLayout(1, 2));
          add(myThreadComboBox);
          add(myThreadFilterField);
        }};

        myThreadsPanel.add(threadComboBoxAndFilterField, BorderLayout.CENTER);
      }
      myToolbar.setAddSeparatorFirst(!invisible);
      updateFrames(activeExecutionStack, session, event == SessionEvent.FRAME_CHANGED ? currentStackFrame : null);
    });
  }

  @Override
  protected void clear() {
    myThreadComboBox.removeAllItems();
    myFramesList.clear();
    myThreadsCalculated = false;
    myExecutionStacksWithSelection.clear();
    myUnfilteredExecutionStacks.clear();
  }

  private void addExecutionStacks(List<? extends XExecutionStack> executionStacks) {
    int count = myThreadComboBox.getItemCount();
    boolean loading = count > 0 && myThreadComboBox.getItemAt(count - 1) == null;
    for (XExecutionStack executionStack : executionStacks) {
      if (!myExecutionStacksWithSelection.contains(executionStack)) {
        if (loading) {
          myThreadComboBox.insertItemAt(executionStack, count - 1); // add right before the loading node
          count++;
        }
        else {
          myUnfilteredExecutionStacks.add(executionStack);
          if (matchesFilter(executionStack)) {
            myThreadComboBox.addItem(executionStack);
          }
        }
        myExecutionStacksWithSelection.put(executionStack, 0);
      }
    }
  }

  private boolean matchesFilter(XExecutionStack stack) {
    final String filterString = myThreadFilterField.getText();
    final String[] filterTerms = filterString.split("\\s+");

    Optional<String> stackTrace;

    synchronized (myExecutionStackToStackTrace) {
      // check if a stack trace is a available for the current execution stack
      stackTrace = myExecutionStackToStackTrace.get(stack);

      if (stackTrace == null) { // stack trace not available yet
        // prevent another computation of this stack trace by putting an empty stack trace to our map
        myExecutionStackToStackTrace.put(stack, Optional.empty());

        // start computation of the stack trace in background
        stack.computeStackFrames(0, new StackTraceBuilder(stack));
        computingStackTraces++;
        if (computingStackTraces == 1) {
          ApplicationManager.getApplication().invokeLater(() -> {
            myThreadFilterField.setBackground(JBColor.YELLOW);
          });
        }

        stackTrace = Optional.empty();
      }
    }

    final Optional<String> finalStackTrace = stackTrace;

    // execution stack is accepted when each filter term occurs in at least one of these elements: thread name, state, stack trace
    return Arrays.stream(filterTerms).allMatch(filter -> {
      return stack.getDisplayName().contains(filter) || finalStackTrace.map(s -> s.contains(filter)).orElse(false);
    });
  }

  private void updateFrames(XExecutionStack executionStack, @NotNull XDebugSession session, @Nullable XStackFrame frameToSelect) {
    if (mySelectedStack != null) {
      getOrCreateBuilder(mySelectedStack, session).stop();
    }

    mySelectedStack = executionStack;
    if (executionStack != null) {
      mySelectedFrameIndex = myExecutionStacksWithSelection.get(executionStack);
      StackFramesListBuilder builder = getOrCreateBuilder(executionStack, session);
      builder.setToSelect(frameToSelect != null ? frameToSelect : mySelectedFrameIndex);
      myListenersEnabled = false;
      boolean selected = builder.initModel(myFramesList.getModel());
      myListenersEnabled = !builder.start() || selected;
    }
  }

  @Override
  public void dispose() {
  }

  public JPanel getMainPanel() {
    return myMainPanel;
  }

  private void processFrameSelection(XDebugSession session, boolean force) {
    mySelectedFrameIndex = myFramesList.getSelectedIndex();
    myExecutionStacksWithSelection.put(mySelectedStack, mySelectedFrameIndex);
    getOrCreateBuilder(mySelectedStack, session).setToSelect(null);
    
    Object selected = myFramesList.getSelectedValue();
    if (selected instanceof XStackFrame) {
      if (session != null) {
        if (force || (!myRefresh && session.getCurrentStackFrame() != selected)) {
          session.setCurrentStackFrame(mySelectedStack, (XStackFrame)selected, mySelectedFrameIndex == 0);
        }
      }
    }
  }

  private class StackFramesListBuilder implements XStackFrameContainerEx {
    private XExecutionStack myExecutionStack;
    private final List<XStackFrame> myStackFrames;
    private String myErrorMessage;
    private int myNextFrameIndex = 0;
    private volatile boolean myRunning;
    private boolean myAllFramesLoaded;
    private final XDebugSession mySession;
    private Object myToSelect;

    private StackFramesListBuilder(final XExecutionStack executionStack, XDebugSession session) {
      myExecutionStack = executionStack;
      mySession = session;
      myStackFrames = new ArrayList<>();
    }

    void setToSelect(Object toSelect) {
      myToSelect = toSelect;
    }

    @Override
    public void addStackFrames(@NotNull final List<? extends XStackFrame> stackFrames, final boolean last) {
      addStackFrames(stackFrames, null, last);
    }
    
    @Override
    public void addStackFrames(@NotNull final List<? extends XStackFrame> stackFrames, @Nullable XStackFrame toSelect, final boolean last) {
      if (isObsolete()) return;
      myLaterInvocator.offer(() -> {
        if (isObsolete()) return;
        myStackFrames.addAll(stackFrames);
        addFrameListElements(stackFrames, last);

        if (toSelect != null) {
          setToSelect(toSelect);
        }

        myNextFrameIndex += stackFrames.size();
        myAllFramesLoaded = last;

        selectCurrentFrame();

        if (last) {
          if (myVisibleRect != null) {
            myFramesList.scrollRectToVisible(myVisibleRect);
          }
          myRunning = false;
          myListenersEnabled = true;
        }
      });
    }

    @Override
    public void errorOccurred(@NotNull final String errorMessage) {
      if (isObsolete()) return;
      myLaterInvocator.offer(() -> {
        if (isObsolete()) return;
        if (myErrorMessage == null) {
          myErrorMessage = errorMessage;
          addFrameListElements(Collections.singletonList(errorMessage), true);
          myRunning = false;
          myListenersEnabled = true;
        }
      });
    }

    private void addFrameListElements(final List<?> values, final boolean last) {
      if (myExecutionStack != null && myExecutionStack == mySelectedStack) {
        DefaultListModel model = myFramesList.getModel();
        int insertIndex = model.size();
        boolean loadingPresent = !model.isEmpty() && model.getElementAt(model.getSize() - 1) == null;
        if (loadingPresent) {
          insertIndex--;
        }
        for (Object value : values) {
          //noinspection unchecked
          model.add(insertIndex++, value);
        }
        if (last) {
          if (loadingPresent) {
            model.removeElementAt(model.getSize() - 1);
          }
        }
        else if (!loadingPresent) {
          //noinspection unchecked
          model.addElement(null);
        }
        myFramesList.repaint();
      }
    }

    @Override
    public boolean isObsolete() {
      return !myRunning;
    }

    public void dispose() {
      myRunning = false;
      myExecutionStack = null;
    }

    public boolean start() {
      if (myExecutionStack == null || myErrorMessage != null) {
        return false;
      }
      myRunning = true;
      myExecutionStack.computeStackFrames(myNextFrameIndex, this);
      return true;
    }

    public void stop() {
      myRunning = false;
    }

    private boolean selectCurrentFrame() {
      if (myToSelect instanceof XStackFrame) {
        if (!Objects.equals(myFramesList.getSelectedValue(), myToSelect) && myFramesList.getModel().contains(myToSelect)) {
          myFramesList.setSelectedValue(myToSelect, true);
          processFrameSelection(mySession, false);
          myListenersEnabled = true;
          return true;
        }
        if (myAllFramesLoaded && myFramesList.getSelectedValue() == null) {
          LOG.error("Frame was not found, " + myToSelect.getClass() + " must correctly override equals");
        }
      }
      else if (myToSelect instanceof Integer) {
        int selectedFrameIndex = (int)myToSelect;
        if (myFramesList.getSelectedIndex() != selectedFrameIndex &&
            myFramesList.getElementCount() > selectedFrameIndex &&
            myFramesList.getModel().get(selectedFrameIndex) != null) {
          myFramesList.setSelectedIndex(selectedFrameIndex);
          processFrameSelection(mySession, false);
          myListenersEnabled = true;
          return true;
        }
      }
      return false;
    }

    @SuppressWarnings("unchecked")
    public boolean initModel(final DefaultListModel model) {
      model.removeAllElements();
      myStackFrames.forEach(model::addElement);
      if (myErrorMessage != null) {
        model.addElement(myErrorMessage);
      }
      else if (!myAllFramesLoaded) {
        model.addElement(null);
      }
      return selectCurrentFrame();
    }
  }
}
