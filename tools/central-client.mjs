/** Central Portal user-managed deployments can resume without replacing their bundle. */
export async function completeDeployment(deploymentId, headers, save, { request = fetch, pause = ms => new Promise(resolve => setTimeout(resolve, ms)), attempts = 80 } = {}) {
  if (!/^[A-Za-z0-9-]+$/.test(deploymentId)) throw Error('Invalid Central deployment identifier');
  const base = 'https://central.sonatype.com/api/v1/publisher';
  let releaseRequested = false;
  for (let i = 0; i < attempts; i++) {
    const response = await request(`${base}/status?id=${encodeURIComponent(deploymentId)}`, { method: 'POST', headers, signal: AbortSignal.timeout(30000) });
    if (!response.ok) throw Error(`Central status failed (${response.status}); resume the recorded deployment`);
    const status = await response.json();
    save({ deploymentId, state: status.deploymentState, published: status.deploymentState === 'PUBLISHED', errors: status.errors });
    if (status.deploymentState === 'PUBLISHED') return status;
    if (status.deploymentState === 'FAILED') throw Error('Central validation failed; inspect deployment.json. Do not replace the version.');
    if (status.deploymentState === 'VALIDATED' && !releaseRequested) {
      const publication = await request(`${base}/deployment/${encodeURIComponent(deploymentId)}`, { method: 'POST', headers, signal: AbortSignal.timeout(30000) });
      if (!publication.ok) throw Error(`Central publication failed (${publication.status}); resume the recorded deployment`);
      releaseRequested = true;
    }
    await pause(15000);
  }
  throw Error('Central deployment remains pending; resume the same recorded deployment, without another upload');
}
