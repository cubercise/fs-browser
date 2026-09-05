import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import { ping, type PingResponse } from './api';

// Frontend seam: the API client is mocked at its module edge so the real
// component tree (render, effects, state) is exercised without a backend.
vi.mock('./api');

const mockedPing = vi.mocked(ping);

describe('App', () => {
  it('displays the root path returned by the ping endpoint', async () => {
    const response: PingResponse = { pong: true, root: '/tmp/fsb-test-root' };
    mockedPing.mockResolvedValue(response);

    render(<App />);

    expect(await screen.findByTestId('ping-root')).toHaveTextContent('/tmp/fsb-test-root');
    expect(screen.getByTestId('ping-pong')).toHaveTextContent('pong: true');
    expect(screen.getByTestId('ping-root')).toHaveTextContent('Root: /tmp/fsb-test-root');
  });

  it('shows a loading state until the ping resolves', () => {
    mockedPing.mockReturnValue(new Promise<PingResponse>(() => {}));

    render(<App />);

    expect(screen.getByTestId('ping-loading')).toBeInTheDocument();
  });
});
