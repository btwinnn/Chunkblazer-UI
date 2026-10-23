# ChunkBlazer UI

The task window, animated task cards, themes, chunk paths and new-player tutorial for the
[ChunkBlazer](https://runelite.net/plugin-hub/show/chunkblazer) plugin.

ChunkBlazer UI needs ChunkBlazer: on its own it does nothing. With both on, ChunkBlazer hands its
in-game UI over to this plugin; turn this one off and ChunkBlazer goes back to its plain cards and
side panel. The two only talk through RuneLite's plugin messages, so neither depends on the other.

## What's in it

- The task window (Tasks on the Points orb) and the quest/saved box, with the quest chunk calculator
- Task cards dealt and flipped with animations, and the task complete popups
- The task box, saved tasks bubble, task outlines and item highlights
- The chunk name banner, world map colour legend and themes (ChunkBlazer, Old School, Varlamore)
- Chunk paths: Shortest Path style routes that stay inside your unlocked chunks
- The new-player tutorial, from Tutorial Island to picking a mode

## Credits

- Chunk paths use the collision map and transports from [Shortest Path](https://github.com/Skretzo/shortest-path)
  by Skretzo (BSD 2-Clause, see `src/main/resources/com/chunkblazerui/pathing/NOTICE.txt`).
- Quest requirements are adapted from [Quest Helper](https://github.com/Zoinkwiz/quest-helper)
  (BSD 2-Clause, see `LICENSE-quest-helper`).

## Developing

Check this project out next to ChunkBlazer (and ChunkBlazer GPU, if you want it), as
`IdeaProjects/Chunkblazer-UI`, `IdeaProjects/Chunkblazer` and `IdeaProjects/Chunkblazer-GPU`. Open
it in IntelliJ as its own project and run `ChunkBlazerUiPluginTest` (src/test) with `-ea`: it
starts RuneLite with ChunkBlazer UI plus whichever of the others are there, from source.
