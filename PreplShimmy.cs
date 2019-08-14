#if NET_4_6
using System;
using UnityEngine;
#if UNITY_EDITOR
using UnityEditor;
#endif
using clojure.lang;

namespace Miracle {
  public class PreplShimmy
  {
    public static bool initialized = false;

    public static Var callbackRunnerVar;
    public static Var startServerVar;
    public static Var initPegoVar;
    
    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
    static void PlayModeInit()
    {
      Initialize();
    }

    #if UNITY_EDITOR
    [InitializeOnLoadMethod]
    static void EditorInit() {
      Initialize();
    }
    #endif

    public static void Initialize () {
      if (!initialized) {
	Debug.Log("Initializing Covalent.PreplShimmy...");
        Arcadia.Util.require("miracle.arcadia.prepl");
	Arcadia.Util.getVar(ref callbackRunnerVar, "miracle.arcadia.prepl", "run-callbacks-hook");
	Arcadia.Util.getVar(ref initPegoVar, "miracle.arcadia.prepl", "init-pego!");
	Arcadia.Util.getVar(ref startServerVar, "miracle.arcadia.prepl", "start-server");
	startServerVar.invoke();
        #if UNITY_EDITOR
	EditorApplication.update += RunCallbacks;
	#else
	initPegoVar.invoke();
	#endif
	initialized = true;
      }
    }

    public static void RunCallbacks() {
      callbackRunnerVar.invoke();
    }

  }
}
#endif
