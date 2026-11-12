# Launcher changelog

## v0.5.2

- Apps open by growing out of the icon you tapped
- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- Widgets are told their real size, so they pick a layout that fits (Wonderdesk widgets and others that support it)
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

## v0.5.1

- There's no separate Launchpad any more: all your apps live in Beacon. The fixed Launchpad button is gone from the Dock, and Beacon sits there as a normal app you can move or remove. Swipe up, Apps in the menu bar and search all open Beacon
- The Dock holds 8 apps
- Apps that change their start screen keep their place on the home screen and Dock
- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.5.0

- Launchpad is now Beacon: swipe up, the Dock button and search open Beacon's Launchpad over a blurred home screen, with folders, six sort orders, hidden apps and full search. Turn it off in Settings › Launchpad to use the built-in one
- Widgets can be removed and moved: hold one and let go for its menu (resize, move, edit, remove), or keep holding and drag it to Remove or another spot. Works even with widgets that handle touches themselves
- Apps can add widgets to the home screen (like Chronicle's “Count down to this”), with a confirm prompt
- Fixed crashes when Beacon and other apps changed folders or the Dock while the home screen was drawing
- Widgets added a moment ago no longer show “Widget unavailable”
- The home screen stays upright when you turn the phone, like Pixel's; turn on “Allow home screen rotation” if you want it to turn
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.4.0

- Home screen rebuilt: drag apps anywhere with a grid guide, drop onto another app to make a folder, drag apps out of folders, and move them between pages by holding at the edge
- Long-press any app, including the ones placed by default, for a menu; drag it to Remove, App info or Uninstall (Uninstall now works)
- Reorder the Dock by dragging; icons resize to fit
- Add and remove pages, arrange them, choose the default page, and turn on endless page scrolling. The launcher remembers the page you were on
- Launchpad has folders, six ways to sort (name, most used, recently used, newly installed, category) and search powered by Beacon, including sums and unit conversions
- Wonderdesk apps no longer appear twice; existing layouts are tidied automatically
- New widget picker with search, previews and sizes. Long-press a widget to resize, move, edit or remove it. If a page is full, the widget goes on the next page and the launcher takes you there
- Original Aqua-style wallpapers built in, or pick any photo
- Tap the date in the menu bar to open Chronicle, or the time to open Timepiece
- Folders open and close with an animation, and app icons refresh by themselves when apps update
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.3.1

- Asks to become your home screen when it isn't, with a quick setup for icons, grid, Dock, menu bar and wallpaper
- Icons keep their own shape and transparency. Wonderdesk apps show their full artwork, and see-through icons are no longer cropped. Prefer uniform icons? Choose Aqua tiles
- Much lighter on the battery: icons are drawn once and cached, apps aren't reloaded every time you go home, and the menu-bar clock only ticks on the minute while visible
- New settings: icon style, Dock reflections, refresh icons, remind me when Wonderdesk isn't the home app
- Double-tap to lock walks you through Android's accessibility switch, including the restricted-settings step
- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.3.0

- Rewritten as an Aqua desktop: menu bar, pages, folders, widgets, a magnifying Dock and Launchpad
- Gestures and full home screen settings
