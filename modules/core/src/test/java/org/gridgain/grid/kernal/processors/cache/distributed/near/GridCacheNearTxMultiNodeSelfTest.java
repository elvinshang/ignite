/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.distributed.near;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.transactions.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.affinity.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;

import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCacheDistributionMode.*;
import static org.gridgain.grid.cache.GridCachePreloadMode.*;
import static org.apache.ignite.transactions.GridCacheTxConcurrency.*;
import static org.apache.ignite.transactions.GridCacheTxIsolation.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;

/**
 * Tests near transactions.
 */
public class GridCacheNearTxMultiNodeSelfTest extends GridCommonAbstractTest {
    /** */
    protected static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** */
    private static final int GRID_CNT = 3;

    /** Number of backups for partitioned tests. */
    protected int backups = 1;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        // Default cache configuration.
        GridCacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setCacheMode(PARTITIONED);
        cacheCfg.setAtomicityMode(TRANSACTIONAL);
        cacheCfg.setDistributionMode(NEAR_PARTITIONED);
        cacheCfg.setWriteSynchronizationMode(FULL_SYNC);
        cacheCfg.setBackups(backups);
        cacheCfg.setPreloadMode(SYNC);

        cfg.setCacheConfiguration(cacheCfg);

        TcpDiscoverySpi spi = new TcpDiscoverySpi();

        spi.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(spi);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        backups = 1;
    }

    /**
     * @throws Exception If failed.
     */
    @SuppressWarnings( {"unchecked"})
    public void testTxCleanup() throws Exception {
        backups = 1;

        Ignite ignite = startGrids(GRID_CNT);

        try {
            Integer mainKey = 0;

            ClusterNode priNode = ignite.cluster().mapKeyToNode(null, mainKey);
            ClusterNode backupNode = F.first(F.view(ignite.cache(null).affinity().mapKeyToPrimaryAndBackups(mainKey),
                F.notIn(F.asList(priNode))));
            ClusterNode otherNode = F.first(ignite.cluster().forPredicate(F.notIn(F.asList(priNode, backupNode))).nodes());

            assert priNode != backupNode;
            assert backupNode != otherNode;
            assert priNode != otherNode;

            Ignite priIgnite = G.ignite(priNode.id());
            Ignite backupIgnite = G.ignite(backupNode.id());
            Ignite otherIgnite = G.ignite(otherNode.id());

            List<Ignite> ignites = F.asList(otherIgnite, priIgnite, backupIgnite);

            int cntr = 0;

            // Update main key from all nodes.
            for (Ignite g : ignites)
                g.cache(null).put(mainKey, ++cntr);

            info("Updated mainKey from all nodes.");

            int keyCnt = 200;

            Collection<Integer> keys = new LinkedList<>();

            // Populate cache from all nodes.
            for (int i = 1; i <= keyCnt; i++) {
                keys.add(i);

                Ignite g = F.rand(ignites);

                g.cache(null).put(new GridCacheAffinityKey<>(i, mainKey), Integer.toString(cntr++));
            }

            GridCacheProjection cache = priIgnite.cache(null).flagsOn(GridCacheFlag.CLONE);

            GridCacheTx tx = cache.txStart(PESSIMISTIC, REPEATABLE_READ);

            try {
                cache.get(mainKey);

                cache.removeAll(keys);

                cache.put(mainKey, ++cntr);

                tx.commit();
            }
            catch (Error | Exception e) {
                error("Transaction failed: " + tx, e);

                throw e;
            } finally {
                tx.close();
            }

            G.stop(priIgnite.name(), true);
            G.stop(backupIgnite.name(), true);

            Ignite newIgnite = startGrid(GRID_CNT);

            ignites = F.asList(otherIgnite, newIgnite);

            for (Ignite g : ignites) {
                GridNearCacheAdapter near = ((GridKernal)g).internalCache().context().near();
                GridDhtCacheAdapter dht = near.dht();

                checkTm(g, near.context().tm());
                checkTm(g, dht.context().tm());
            }
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testTxReadersUpdate() throws Exception {
        startGridsMultiThreaded(GRID_CNT);

        try {
            testReadersUpdate(OPTIMISTIC, REPEATABLE_READ);

            testReadersUpdate(PESSIMISTIC, REPEATABLE_READ);
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @param concurrency Transaction concurrency.
     * @param isolation Transaction isolation.
     * @throws Exception If failed.
     */
    private void testReadersUpdate(GridCacheTxConcurrency concurrency, GridCacheTxIsolation isolation) throws Exception {
        GridCache<Integer, Integer> cache = grid(0).cache(null);

        try (GridCacheTx tx = cache.txStart(concurrency, isolation)) {
            for (int i = 0; i < 100; i++)
                cache.put(i, 1);

            tx.commit();
        }

        // Create readers.
        for (int g = 0; g < GRID_CNT; g++) {
            GridCache<Integer, Integer> c = grid(g).cache(null);

            for (int i = 0; i < 100; i++)
                assertEquals((Integer)1, c.get(i));
        }

        try (GridCacheTx tx = cache.txStart(concurrency, isolation)) {
            for (int i = 0; i < 100; i++)
                cache.put(i, 2);

            tx.commit();
        }

        for (int g = 0; g < GRID_CNT; g++) {
            GridCache<Integer, Integer> c = grid(g).cache(null);

            for (int i = 0; i < 100; i++)
                assertEquals((Integer)2, c.get(i));
        }
    }

    /**
     * @param g Grid.
     * @param tm Transaction manager.
     */
    @SuppressWarnings( {"unchecked"})
    private void checkTm(Ignite g, GridCacheTxManager tm) {
        Collection<GridCacheTxEx> txs = tm.txs();

        info(">>> Number of transactions in the set [size=" + txs.size() +
            ", nodeId=" + g.cluster().localNode().id() + ']');

        for (GridCacheTxEx tx : txs)
            assert tx.done() : "Transaction is not finished: " + tx;
    }
}
