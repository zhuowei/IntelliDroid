/*
 * GUIHierarchyPrinterClient.java - part of the GATOR project
 *
 * Copyright (c) 2014, 2015 The Ohio State University
 * Modified for Intellidroid: set path, add WTG dumping from WTGDemoClient
 * and use JNI/Wala style method signatures
 *
 * This file is distributed under the terms described in LICENSE in the
 * root directory.
 */
package intellidroid.appanalysis.gator;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
// Intellidroid: WTG
import java.util.Collection;

import presto.android.Configs;
import presto.android.gui.GUIAnalysisClient;
import presto.android.gui.GUIAnalysisOutput;
import presto.android.gui.rep.GUIHierarchy;
import presto.android.gui.rep.GUIHierarchy.Activity;
import presto.android.gui.rep.GUIHierarchy.Dialog;
import presto.android.gui.rep.GUIHierarchy.EventAndHandler;
import presto.android.gui.rep.GUIHierarchy.View;
import presto.android.gui.rep.StaticGUIHierarchy;

// Intellidroid: WTG
import presto.android.gui.wtg.EventHandler;
import presto.android.gui.wtg.StackOperation;
import presto.android.gui.wtg.WTGAnalysisOutput;
import presto.android.gui.wtg.WTGBuilder;
import presto.android.gui.wtg.ds.WTG;
import presto.android.gui.wtg.ds.WTGEdge;
import presto.android.gui.wtg.ds.WTGNode;
import presto.android.gui.wtg.flowgraph.NLauncherNode;


import soot.Scene;
import soot.SootMethod;

// Intellidroid: dump WTG using JSON. (We keep the original XML of the GUIAnalysisClient since it works well enough)
import org.json.JSONObject;
import org.json.JSONArray;

public class GUIHierarchyPrinterClient implements GUIAnalysisClient {
  GUIAnalysisOutput output;
  GUIHierarchy guiHier;

  private PrintStream out;
  private int indent;

  void printf(String format, Object... args) {
    for (int i = 0; i < indent; i++) {
      out.print(' ');
    }
    out.printf(format, args);
  }

  void log(String s) {
    System.out.println(
        "\033[1;31m[GUIHierarchyPrinterClient] " + s + "\033[0m");
  }

  @Override
  public void run(GUIAnalysisOutput output) {
    this.output = output;
    guiHier = new StaticGUIHierarchy(output);

    // Init the file io
    try {
      // Intellidroid: get file path from client params
      File file = new File(Configs.pathoutfilename, "gui.xml");
      file.getParentFile().mkdirs();
      log("XML file: " + file.getAbsolutePath());
      out = new PrintStream(file);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // Start printing
    printf("<GUIHierarchy app=\"%s\">\n", guiHier.app);
    printActivities();
    printDialogs();
    printf("</GUIHierarchy>\n");

    // Finish
    out.flush();
    out.close();
    // Intellidroid: add WTG dumping
    // WIP: disabled for now, since Gator freezes when processing K9 v5.502
    /*
    try {
      dumpWTG(output);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    */
  }

  void printRootViewAndHierarchy(ArrayList<View> roots) {
    indent += 2;
    for (View rootView : roots) {
      printView(rootView);
    }
    indent -= 2;
  }

  void printActivities() {
    for (Activity act : guiHier.activities) {
      indent += 2;
      printf("<Activity name=\"%s\">\n", act.name);

      // Roots & view hierarchy (including OptionsMenu)
      printRootViewAndHierarchy(act.views);

      printf("</Activity>\n");
      indent -= 2;
    }
  }

  void printDialogs() {
    for (Dialog dialog : guiHier.dialogs) {
      indent += 2;
      printf("<Dialog name=\"%s\" allocLineNumber=\"%d\" allocStmt=\"%s\" allocMethod=\"%s\">\n",
          dialog.name, dialog.allocLineNumber,
          xmlSafe(dialog.allocStmt), xmlSafe(dialog.allocMethod));
      printRootViewAndHierarchy(dialog.views);
      printf("</Dialog>\n");
      indent -= 2;
    }
  }

  public String xmlSafe(String s) {
    return s
        .replaceAll("&", "&amp;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&apos;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;");

  }

  // WARNING: remember to remove the node before exit. Very prone to error!!!
  void printView(View view) {
    // <View type=... id=... idName=... text=... title=...>
    //   <View ...>
    //     ...
    //   </View>
    //   <EventAndHandler event=... handler=... />
    // </View>

    String type = String.format(" type=\"%s\"", view.type);
    String id = String.format(" id=\"%d\"", view.id);
    String idName = String.format(" idName=\"%s\"", view.idName);
    // TODO(tony): add the text attribute for TextView and so on
    String text = "";
    // title for MenuItem
    String title = "";
    if (view.title != null) {
      if (!type.contains("MenuItem")) {
        throw new RuntimeException(type + " has a title field!");
      }
      title = String.format(" title=\"%s\"", xmlSafe(view.title));
    }
    String head =
        String.format("<View%s%s%s%s%s>\n", type, id, idName, text, title);
    printf(head);

    {
      // This includes both children and context menus
      for (View child : view.views) {
        indent += 2;
        printView(child);
        indent -= 2;
      }
      // Events and handlers
      for (EventAndHandler eventAndHandler : view.eventAndHandlers) {
        indent += 2;
        String handler = eventAndHandler.handler;
        String safeRealHandler = "";
        if (handler.startsWith("<FakeName_")) {
          SootMethod fake = Scene.v().getMethod(handler);
          SootMethod real = output.getRealHandler(fake);
          // Intellidroid: use bytecode signature
          safeRealHandler = String.format(
              " realHandler=\"%s\"", xmlSafe(real.getBytecodeSignature()));
        }
        // Intellidroid: use bytecode signature
        printf("<EventAndHandler event=\"%s\" handler=\"%s\"%s />\n",
            eventAndHandler.event, xmlSafe(Scene.v().getMethod(eventAndHandler.handler).getBytecodeSignature()), safeRealHandler);
        indent -= 2;
      }
    }

    String tail = "</View>\n";
    printf(tail);
  }

  // Taken from WTGDemoClient; modified to dump JSON out.
  private void dumpWTG(GUIAnalysisOutput output) throws Exception {
    log("WTGBuilder");
    WTGBuilder wtgBuilder = new WTGBuilder();
    wtgBuilder.build(output);
    log("WTGAnalysisOutput");
    WTGAnalysisOutput wtgAO = new WTGAnalysisOutput(output, wtgBuilder);
    WTG wtg = wtgAO.getWTG();

    Collection<WTGEdge> edges = wtg.getEdges();
    Collection<WTGNode> nodes = wtg.getNodes();

    log("Num edges: " + edges.size() + " Num nodes: " + nodes.size());

    JSONObject outObj = new JSONObject();
    outObj.put("application", Configs.benchmarkName);
    outObj.put("launcherNode", wtg.getLauncherNode());

    JSONArray outNodes = new JSONArray();

    for (WTGNode n : nodes){
      JSONObject outNode = new JSONObject();
      outNode.put("window", n.getWindow().toString());
      outNodes.put(outNode);
    }

    outObj.put("nodes", outNodes);

    JSONArray outEdges = new JSONArray();

    for (WTGEdge e : edges){
      JSONObject outEdge = new JSONObject();
      outEdge.put("sourceWindow", e.getSourceNode().getWindow().toString());
      outEdge.put("targetWindow", e.getTargetNode().getWindow().toString());
      outEdge.put("eventType", e.getEventType().toString());

      JSONArray outEventHandlers = new JSONArray();
      for (SootMethod m : e.getEventHandlers()) {
        outEventHandlers.put(m.toString());
      }
      outEdge.put("eventHandlers", outEventHandlers);

      JSONArray outCallbacks = new JSONArray();
      for (EventHandler eh : e.getCallbacks()) {
        outCallbacks.put(eh.getEventHandler().toString());
      }

      outEdge.put("callbacks", outCallbacks);

      JSONArray outStackOps = new JSONArray();
      for (StackOperation s : e.getStackOps()){
        JSONObject outStackOp = new JSONObject();
        outStackOp.put("op", s.isPushOp()? "push": "pop");
        outStackOp.put("window", s.getWindow().toString());
        outStackOps.put(outStackOp);
      }
      outEdge.put("stackOps", outStackOps);
      outEdges.put(outEdge);
    }
    outObj.put("edges", outEdges);

    // todo: use the DFS path generator to pregenerate all Launcher -> Activity paths

    PrintStream outStream = null;
    try {
        outStream = new PrintStream(new File(Configs.pathoutfilename, "wtg.json"));
        outStream.println(outObj.toString(4));
    } finally {
        if (outStream != null) outStream.close();
    }
  }

}
