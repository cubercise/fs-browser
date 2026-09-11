import { createRootRoute, createRoute, createRouter, redirect } from '@tanstack/react-router';
import BrowsePage from './BrowsePage';

/**
 * Route tree, typed once and exported so main and tests each create their
 * own router (browser history in the app, memory history in tests — both
 * render the real tree). The browse page's position lives in the URL
 * (`/browse?path=…`), so deep links and browser history work; `/` redirects.
 */
const rootRoute = createRootRoute();

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/browse' });
  },
});

export const browseRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/browse',
  component: BrowsePage,
  validateSearch: (search: Record<string, unknown>): { path?: string } => {
    if (typeof search.path !== 'string' || search.path === '') {
      return {};
    }
    const raw = search.path;
    // Traversal-shaped URLs are malformed input, not directories: strip them
    // so the page simply shows the Root instead of asking the server.
    const safe = !raw.startsWith('/') && !raw.includes('\\') && !raw.split('/').includes('..');
    return safe ? { path: raw } : {};
  },
});

export const routeTree = rootRoute.addChildren([indexRoute, browseRoute]);

export function createAppRouter(history?: Parameters<typeof createRouter>[0]['history']) {
  const router = createRouter({ routeTree, history });
  return router;
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
