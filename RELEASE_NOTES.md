## BrightControl v4.47 -- bind a button to a shortcut inside an app

**A button or an edge swipe can now open one of the shortcuts of an app, not only the app.** For example, a hold of the camera button can open Todoist's "Add task" screen. This was requested in BrightControl#1.

To set it, open Buttons or Edge gestures, select a gesture, and then select **A shortcut inside an app**. The screen has two lists:

SHORTCUTS shows the shortcuts that each app declares in its manifest. Select one to bind it.

MAKE ONE IN THE APP shows the apps that make a shortcut after a question, for example which project or which contact. Select one, answer the question in that app, and BrightControl binds the result.

Limits:

Android gives the full shortcut list of an app only to the default home screen app. Thus BrightControl cannot show the shortcuts that an app adds while it runs, for example recent chats.

Some apps keep the screen of a shortcut private. A launcher can open it, but BrightControl cannot. The list shows these shortcuts dimmed.

If an app update removes a bound shortcut, the binding opens the app instead.

The key log shows the app and the name of the shortcut. It does not show the data in the shortcut.
