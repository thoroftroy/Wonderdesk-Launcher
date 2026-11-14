# Launcher changelog

## v0.5.12

- Add a widget shows the real widgets, drawn live (Android 12 and newer), instead of app icons, so you can see what you're adding

## v0.5.11

- While you drag an app or widget, the other icons wiggle gently, like rearranging icons on an iPhone (only during a drag, so no battery cost)
- Fixed a crash when moving around with arrow keys or Tab while a screen was closing

## v0.5.10

- Dock icons bounce when you open an app, like the Mac Dock

## v0.5.9

- Lighter on the battery: in the Super look the drifting sparkles settle after a few seconds without a touch (the next tap wakes them), so a screen you're reading no longer redraws 25 times a second

## v0.5.8

- Changing the look in Control Room now restyles this app's widgets straight away, with no refresh or restart

## v0.5.7

- Icons on the home screen and in the Dock sink in under your finger and spring back, like real buttons
- Swiping between pages turns them like cards: the page tilts, eases back and fades instead of sliding flat

## v0.5.6

- Desktop sounds: a soft pop when an app opens, whooshes when folders and Beacon open and close, and a puff when you remove something. Silent whenever your ringer is off; switch them off in Home screen settings › Interface sounds

## v0.5.5

- Wallpaper editor: pinch and drag to frame any picture with your icons, Dock and menu bar shown on top, dim it, then set it for the home screen, lock screen or both
- Day and night wallpapers: keep one for daytime and one for night, and they swap by themselves at the hours you pick
- The Home button always takes you back to the desktop, even from Settings, the wallpaper editor or the widget list

## v0.5.4

- Icon packs: Home screen settings › Icon pack works with any pack made for Nova, Lawnchair and other launchers. Apps a pack doesn't cover keep their own icon
- Fixes from a full round of stress testing on every screen

## v0.5.3

- Switches Android greys out as a “Restricted setting” are now explained: the guide walks you through unlocking them in three clear steps, and keeps showing them until the switch is really on (before, on Android 15 and newer it could hide the only hint)
- Tap the tab you're already on to glide back to the top of its list
- The menu bar, folders, app list and messages follow your look instead of always being light grey
- Nothing required stays hidden: if Android is blocking something the app needs, a slim banner says what and takes you straight to the switch, and Settings › Permissions and access lists everything with its state

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
