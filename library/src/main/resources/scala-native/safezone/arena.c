// Thread-safe replacement for scala-native's own `zone/` runtime, which keeps
// a single global page pool with no synchronization at all.
//
// - Each zone bump-allocates into its own current page without any locking.
// - Only taking pages from / giving pages back to the shared free list takes
//   the pool lock (at most once per SAFEZONE_PAGE_SIZE bytes allocated, plus
//   once on close).
// - Pages in use by a zone are registered as GC roots, so GC objects
//   referenced from zone objects stay alive. scala-native's root registry is
//   itself mutex-protected.
//
// A single zone is not meant to be shared between threads: `RealSafeZone`
// rejects allocations from any thread other than the one that opened it.
#if defined(SCALANATIVE_COMPILE_ALWAYS) || defined(__SAFEZONE_ARENA)

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
static SRWLOCK pool_lock = SRWLOCK_INIT;
#define POOL_LOCK() AcquireSRWLockExclusive(&pool_lock)
#define POOL_UNLOCK() ReleaseSRWLockExclusive(&pool_lock)
#else
#include <pthread.h>
static pthread_mutex_t pool_lock = PTHREAD_MUTEX_INITIALIZER;
#define POOL_LOCK() pthread_mutex_lock(&pool_lock)
#define POOL_UNLOCK() pthread_mutex_unlock(&pool_lock)
#endif

// Provided by every scala-native GC (`gc/shared/ScalaNativeGC.h`).
void scalanative_GC_add_roots(void *addr_low, void *addr_high);
void scalanative_GC_remove_roots(void *addr_low, void *addr_high);

#define SAFEZONE_PAGE_SIZE 8192
#define SAFEZONE_ALIGN 8

typedef struct Page {
    struct Page *next;
    size_t size;
    size_t offset;
    // followed by `size` bytes of data
} Page;

typedef struct {
    Page *page;  // current small page first, older ones chained via `next`
    Page *large; // dedicated pages for objects bigger than a small page
} Zone;

// Small pages given back by closed zones, ready for reuse. Guarded by
// `pool_lock`.
static Page *free_pages = NULL;

// Number of small pages handed out to zones so far, for tests/diagnostics.
static size_t page_claims = 0;

static inline char *Page_data(Page *page) { return (char *)(page + 1); }

static inline void Page_add_roots(Page *page) {
    scalanative_GC_add_roots(Page_data(page), Page_data(page) + page->size);
}

static inline void Page_remove_roots(Page *page) {
    scalanative_GC_remove_roots(Page_data(page), Page_data(page) + page->size);
}

static Page *Page_new(size_t size) {
    Page *page = malloc(sizeof(Page) + size);
    if (page == NULL) {
        abort();
    }
    page->size = size;
    return page;
}

static Page *claim_page(void) {
    POOL_LOCK();
    Page *page = free_pages;
    if (page != NULL) {
        free_pages = page->next;
    }
    page_claims++;
    POOL_UNLOCK();
    if (page == NULL) {
        page = Page_new(SAFEZONE_PAGE_SIZE);
    }
    page->next = NULL;
    page->offset = 0;
    Page_add_roots(page);
    return page;
}

void *safezone_arena_open(void) {
    Zone *zone = malloc(sizeof(Zone));
    if (zone == NULL) {
        abort();
    }
    zone->page = NULL;
    zone->large = NULL;
    return zone;
}

void *safezone_arena_alloc(void *rawzone, void *info, size_t size) {
    Zone *zone = (Zone *)rawzone;
    size = (size + SAFEZONE_ALIGN - 1) & ~(size_t)(SAFEZONE_ALIGN - 1);
    char *result;
    if (size > SAFEZONE_PAGE_SIZE) {
        Page *page = Page_new(size);
        page->offset = size;
        page->next = zone->large;
        zone->large = page;
        Page_add_roots(page);
        result = Page_data(page);
    } else {
        Page *page = zone->page;
        if (page == NULL || page->offset + size > page->size) {
            Page *fresh = claim_page();
            fresh->next = page;
            zone->page = fresh;
            page = fresh;
        }
        result = Page_data(page) + page->offset;
        page->offset += size;
    }
    memset(result, 0, size);
    *(void **)result = info;
    return result;
}

void safezone_arena_close(void *rawzone) {
    Zone *zone = (Zone *)rawzone;

    Page *head = zone->page, *tail = NULL;
    for (Page *page = head; page != NULL; page = page->next) {
        Page_remove_roots(page);
        tail = page;
    }
    if (tail != NULL) {
        POOL_LOCK();
        tail->next = free_pages;
        free_pages = head;
        POOL_UNLOCK();
    }

    Page *large = zone->large;
    while (large != NULL) {
        Page *next = large->next;
        Page_remove_roots(large);
        free(large);
        large = next;
    }

    free(zone);
}

size_t safezone_arena_page_claims(void) {
    POOL_LOCK();
    size_t result = page_claims;
    POOL_UNLOCK();
    return result;
}

#endif
