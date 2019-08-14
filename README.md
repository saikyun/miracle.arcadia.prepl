# miracle.arcadia.prepl
PREPL for Arcadia

# Dependencies
The datafy-nav-tap branch of Arcadia that sogaiu made: https://github.com/sogaiu/Arcadia/tree/datafy-nav-tap

# Installation
Make a directory named `miracle/arcadia` in your `Assets/`-folder and `cd` to it.
```
git clone https://github.com/Saikyun/miracle.arcadia.prepl prepl
```

Let Unity compile `PreplShimmy.cs`

You should then see a message in Unity saying that a PREPL-server has started. :)

Done!

# Usage
In a terminal:
```
nc 127.0.0.1 7653
1
{:tag :ret, :val "1", :ns "user", :ms 24.0582, :form "1"}
2
{:tag :ret, :val "2", :ns "user", :ms 15.8792, :form "2"}
(* 5 5)
{:tag :ret, :val "25", :ns "user", :ms 8.855, :form "(* 5 5)"}
(ns game.core)
{:tag :ret, :val "nil", :ns "game.core", :ms 22.5502, :form "(ns game.core)"}
(use 'arcadia.core)
{:tag :ret, :val "nil", :ns "game.core", :ms 7.1008, :form "(use 'arcadia.core)"}
(object-named "Main Camera")
{:tag :ret, :val "#<Main Camera (UnityEngine.GameObject)>", :ns "game.core", :ms 38.1242, :form "(object-named \"Main Camera\")"}
```
