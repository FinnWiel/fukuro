# Series layout change

This change replaces the series cover grid with a cover-led overview designed
around the information Audiobookshelf already provides.

## What changes

- The active book cover supplies a blurred, darkened backdrop.
- Up to seven series covers form a fan with the active book on top. Covers
  progressively move behind it according to their distance from the active book.
- The path-traveled strip shows aggregate progress. Each segment width reflects
  that audiobook's share of the series runtime.
- The current book gets a resume card, followed by the complete book journey.
- Android system Back first collapses the full player to the mini player while
  preserving the series page underneath; the next Back leaves the series.
- Existing favorite, shortcut, download, removal, navigation, and playback actions
  remain available.

## Performance

- The backdrop is decoded at the low resolution appropriate for a heavy blur.
- The blurred render layer is limited to the coloured upper portion of the page.
- Cover model lookups are cached for the lifetime of the series screen.
- The journey remains lazy, so off-screen rows are not composed on entry.
- Home shelf grouping and Library filtering/sorting run away from the UI thread,
  keeping bottom-tab navigation responsive for large libraries.
- Settings uses a lazy list, so controls below the screen are not composed until
  they are scrolled into view.

## Scope

This is the default series layout and adds no appearance preference. It does not
visually change the Home, Library, Settings, or player layouts, and it does not introduce
ebook/audio alignment, reading, bookmarks, or listening-history features.
