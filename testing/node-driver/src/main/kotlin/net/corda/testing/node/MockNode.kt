package net.corda.testing.node

import com.google.common.jimfs.Configuration.unix
import com.google.common.jimfs.Jimfs
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.crypto.entropyToKeyPair
import net.corda.core.crypto.random63BitValue
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.concurrent.doneFuture
import net.corda.core.internal.createDirectories
import net.corda.core.internal.createDirectory
import net.corda.core.messaging.MessageRecipients
import net.corda.core.messaging.RPCOps
import net.corda.core.messaging.SingleMessageRecipient
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.node.ServiceEntry
import net.corda.core.node.WorldMapLocation
import net.corda.core.node.services.*
import net.corda.core.utilities.*
import net.corda.node.internal.AbstractNode
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.identity.PersistentIdentityService
import net.corda.node.services.keys.E2ETestKeyManagementService
import net.corda.node.services.messaging.MessagingService
import net.corda.node.services.network.InMemoryNetworkMapService
import net.corda.node.services.network.NetworkMapService
import net.corda.node.services.transactions.*
import net.corda.node.utilities.AffinityExecutor
import net.corda.node.utilities.AffinityExecutor.ServiceAffinityExecutor
import net.corda.node.utilities.CertificateAndKeyPair
import net.corda.testing.*
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import org.apache.activemq.artemis.utils.ReusableLatch
import org.bouncycastle.asn1.x500.X500Name
import org.slf4j.Logger
import java.math.BigInteger
import java.nio.file.Path
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A mock node brings up a suite of in-memory services in a fast manner suitable for unit testing.
 * Components that do IO are either swapped out for mocks, or pointed to a [Jimfs] in memory filesystem or an in
 * memory H2 database instance.
 *
 * Mock network nodes require manual pumping by default: they will not run asynchronous. This means that
 * for message exchanges to take place (and associated handlers to run), you must call the [runNetwork]
 * method.
 *
 * You can get a printout of every message sent by using code like:
 *
 *    LogHelper.setLevel("+messages")
 */
class MockNetwork(private val networkSendManuallyPumped: Boolean = false,
                  private val threadPerNode: Boolean = false,
                  servicePeerAllocationStrategy: InMemoryMessagingNetwork.ServicePeerAllocationStrategy =
                  InMemoryMessagingNetwork.ServicePeerAllocationStrategy.Random(),
                  private val defaultFactory: Factory<*> = MockNetwork.DefaultFactory,
                  private val initialiseSerialization: Boolean = true) {
    var nextNodeId = 0
        private set
    private val filesystem = Jimfs.newFileSystem(unix())
    private val busyLatch = ReusableLatch()
    val messagingNetwork = InMemoryMessagingNetwork(networkSendManuallyPumped, servicePeerAllocationStrategy, busyLatch)
    // A unique identifier for this network to segregate databases with the same nodeID but different networks.
    private val networkId = random63BitValue()
    private val _nodes = mutableListOf<MockNode>()
    /** A read only view of the current set of executing nodes. */
    val nodes: List<MockNode> get() = _nodes

    init {
        if (initialiseSerialization) initialiseTestSerialization()
        filesystem.getPath("/nodes").createDirectory()
    }

    /** Allows customisation of how nodes are created. */
    interface Factory<out N : MockNode> {
        /**
         * @param overrideServices a set of service entries to use in place of the node's default service entries,
         * for example where a node's service is part of a cluster.
         * @param entropyRoot the initial entropy value to use when generating keys. Defaults to an (insecure) random value,
         * but can be overriden to cause nodes to have stable or colliding identity/service keys.
         */
        fun create(config: NodeConfiguration, network: MockNetwork, networkMapAddr: SingleMessageRecipient?,
                   advertisedServices: Set<ServiceInfo>, id: Int, overrideServices: Map<ServiceInfo, KeyPair>?,
                   entropyRoot: BigInteger): N
    }

    object DefaultFactory : Factory<MockNode> {
        override fun create(config: NodeConfiguration, network: MockNetwork, networkMapAddr: SingleMessageRecipient?,
                            advertisedServices: Set<ServiceInfo>, id: Int, overrideServices: Map<ServiceInfo, KeyPair>?,
                            entropyRoot: BigInteger): MockNode {
            return MockNode(config, network, networkMapAddr, advertisedServices, id, overrideServices, entropyRoot)
        }
    }

    /**
     * Because this executor is shared, we need to be careful about nodes shutting it down.
     */
    private val sharedUserCount = AtomicInteger(0)
    private val sharedServerThread = object : ServiceAffinityExecutor("Mock network", 1) {
        override fun shutdown() {
            // We don't actually allow the shutdown of the network-wide shared thread pool until all references to
            // it have been shutdown.
            if (sharedUserCount.decrementAndGet() == 0) {
                super.shutdown()
            }
        }

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            if (!isShutdown) {
                flush()
                return true
            } else {
                return super.awaitTermination(timeout, unit)
            }
        }
    }

    /**
     * @param overrideServices a set of service entries to use in place of the node's default service entries,
     * for example where a node's service is part of a cluster.
     * @param entropyRoot the initial entropy value to use when generating keys. Defaults to an (insecure) random value,
     * but can be overriden to cause nodes to have stable or colliding identity/service keys.
     */
    open class MockNode(config: NodeConfiguration,
                        val mockNet: MockNetwork,
                        override val networkMapAddress: SingleMessageRecipient?,
                        advertisedServices: Set<ServiceInfo>,
                        val id: Int,
                        val overrideServices: Map<ServiceInfo, KeyPair>?,
                        val entropyRoot: BigInteger = BigInteger.valueOf(random63BitValue())) :
            AbstractNode(config, advertisedServices, TestClock(), mockNet.busyLatch) {
        var counter = entropyRoot
        override val log: Logger = loggerFor<MockNode>()
        override val platformVersion: Int get() = 1
        override val serverThread: AffinityExecutor =
                if (mockNet.threadPerNode)
                    ServiceAffinityExecutor("Mock node $id thread", 1)
                else {
                    mockNet.sharedUserCount.incrementAndGet()
                    mockNet.sharedServerThread
                }

        // We only need to override the messaging service here, as currently everything that hits disk does so
        // through the java.nio API which we are already mocking via Jimfs.
        override fun makeMessagingService(legalIdentity: PartyAndCertificate): MessagingService {
            require(id >= 0) { "Node ID must be zero or positive, was passed: " + id }
            return mockNet.messagingNetwork.createNodeWithID(
                    !mockNet.threadPerNode,
                    id,
                    serverThread,
                    makeServiceEntries(),
                    myLegalName,
                    database)
                    .start()
                    .getOrThrow()
        }

        override fun makeIdentityService(trustRoot: X509Certificate,
                                         clientCa: CertificateAndKeyPair?,
                                         legalIdentity: PartyAndCertificate): IdentityService {
            val caCertificates: Array<X509Certificate> = listOf(legalIdentity.certificate.cert, clientCa?.certificate?.cert)
                    .filterNotNull()
                    .toTypedArray()
            val identityService = PersistentIdentityService(setOf(info.legalIdentityAndCert),
                    trustRoot = trustRoot, caCertificates = *caCertificates)
            services.networkMapCache.partyNodes.forEach { identityService.verifyAndRegisterIdentity(it.legalIdentityAndCert) }
            services.networkMapCache.changed.subscribe { mapChange ->
                // TODO how should we handle network map removal
                if (mapChange is NetworkMapCache.MapChange.Added) {
                    identityService.verifyAndRegisterIdentity(mapChange.node.legalIdentityAndCert)
                }
            }

            return identityService
        }

        override fun makeKeyManagementService(identityService: IdentityService): KeyManagementService {
            return E2ETestKeyManagementService(identityService, partyKeys + (overrideServices?.values ?: emptySet()))
        }

        override fun startMessagingService(rpcOps: RPCOps) {
            // Nothing to do
        }

        override fun makeNetworkMapService() {
            inNodeNetworkMapService = InMemoryNetworkMapService(services, platformVersion)
        }

        override fun makeServiceEntries(): List<ServiceEntry> {
            val defaultEntries = super.makeServiceEntries()
            return if (overrideServices == null) {
                defaultEntries
            } else {
                defaultEntries.map {
                    val override = overrideServices[it.info]
                    if (override != null) {
                        // TODO: Store the key
                        ServiceEntry(it.info, getTestPartyAndCertificate(it.identity.name, override.public))
                    } else {
                        it
                    }
                }
            }
        }

        // This is not thread safe, but node construction is done on a single thread, so that should always be fine
        override fun generateKeyPair(): KeyPair {
            counter = counter.add(BigInteger.ONE)
            return entropyToKeyPair(counter)
        }

        // It's OK to not have a network map service in the mock network.
        override fun noNetworkMapConfigured() = doneFuture(Unit)

        // There is no need to slow down the unit tests by initialising CityDatabase
        open fun findMyLocation(): WorldMapLocation? = null // It's left only for NetworkVisualiserSimulation

        override fun makeTransactionVerifierService() = InMemoryTransactionVerifierService(1)

        override fun myAddresses() = emptyList<NetworkHostAndPort>()

        // Allow unit tests to modify the plugin list before the node start,
        // so they don't have to ServiceLoad test plugins into all unit tests.
        val testPluginRegistries = super.pluginRegistries.toMutableList()
        override val pluginRegistries: List<CordaPluginRegistry>
            get() = testPluginRegistries

        // This does not indirect through the NodeInfo object so it can be called before the node is started.
        // It is used from the network visualiser tool.
        @Suppress("unused") val place: WorldMapLocation get() = findMyLocation()!!

        fun pumpReceive(block: Boolean = false): InMemoryMessagingNetwork.MessageTransfer? {
            return (network as InMemoryMessagingNetwork.InMemoryMessaging).pumpReceive(block)
        }

        fun disableDBCloseOnStop() {
            runOnStop.remove(dbCloser)
        }

        fun manuallyCloseDB() {
            dbCloser?.invoke()
            dbCloser = null
        }

        // You can change this from zero if you have custom [FlowLogic] that park themselves.  e.g. [StateMachineManagerTests]
        var acceptableLiveFiberCountOnStop: Int = 0

        override fun acceptableLiveFiberCountOnStop(): Int = acceptableLiveFiberCountOnStop

        override fun makeCoreNotaryService(type: ServiceType): NotaryService? {
            if (type != BFTNonValidatingNotaryService.type) return super.makeCoreNotaryService(type)
            return BFTNonValidatingNotaryService(services, object : BFTSMaRt.Cluster {
                override fun waitUntilAllReplicasHaveInitialized() {
                    val clusterNodes = mockNet.nodes.filter {
                        services.notaryIdentityKey in it.info.serviceIdentities(BFTNonValidatingNotaryService.type).map { it.owningKey }
                    }
                    if (clusterNodes.size != configuration.notaryClusterAddresses.size) {
                        throw IllegalStateException("Unable to enumerate all nodes in BFT cluster.")
                    }
                    clusterNodes.forEach {
                        val notaryService = it.smm.findServices { it is BFTNonValidatingNotaryService }.single() as BFTNonValidatingNotaryService
                        notaryService.waitUntilReplicaHasInitialized()
                    }
                }
            })
        }

        /**
         * Makes sure that the [MockNode] is correctly registered on the [MockNetwork]
         * Please note that [MockNetwork.runNetwork] should be invoked to ensure that all the pending registration requests
         * were duly processed
         */
        fun ensureRegistered() {
            _nodeReadyFuture.getOrThrow()
        }
    }

    /**
     * Returns a node, optionally created by the passed factory method.
     * @param overrideServices a set of service entries to use in place of the node's default service entries,
     * for example where a node's service is part of a cluster.
     * @param entropyRoot the initial entropy value to use when generating keys. Defaults to an (insecure) random value,
     * but can be overridden to cause nodes to have stable or colliding identity/service keys.
     * @param configOverrides add/override behaviour of the [NodeConfiguration] mock object.
     */
    fun createNode(networkMapAddress: SingleMessageRecipient? = null, forcedID: Int? = null,
                   start: Boolean = true, legalName: X500Name? = null, overrideServices: Map<ServiceInfo, KeyPair>? = null,
                   entropyRoot: BigInteger = BigInteger.valueOf(random63BitValue()),
                   vararg advertisedServices: ServiceInfo,
                   configOverrides: (NodeConfiguration) -> Any? = {}): MockNode {
        return createNode(networkMapAddress, forcedID, defaultFactory, start, legalName, overrideServices, entropyRoot, *advertisedServices, configOverrides = configOverrides)
    }

    /** Like the other [createNode] but takes a [Factory] and propagates its [MockNode] subtype. */
    fun <N : MockNode> createNode(networkMapAddress: SingleMessageRecipient? = null, forcedID: Int? = null, nodeFactory: Factory<N>,
                   start: Boolean = true, legalName: X500Name? = null, overrideServices: Map<ServiceInfo, KeyPair>? = null,
                   entropyRoot: BigInteger = BigInteger.valueOf(random63BitValue()),
                   vararg advertisedServices: ServiceInfo,
                   configOverrides: (NodeConfiguration) -> Any? = {}): N {
        val id = forcedID ?: nextNodeId++
        val config = testNodeConfiguration(
                baseDirectory = baseDirectory(id).createDirectories(),
                myLegalName = legalName ?: getX500Name(O = "Mock Company $id", L = "London", C = "GB")).also {
            whenever(it.dataSourceProperties).thenReturn(makeTestDataSourceProperties("node_${id}_net_$networkId"))
            configOverrides(it)
        }
        return nodeFactory.create(config, this, networkMapAddress, advertisedServices.toSet(), id, overrideServices, entropyRoot).apply {
            if (start) {
                start()
                if (threadPerNode && networkMapAddress != null) nodeReadyFuture.getOrThrow() // XXX: What about manually-started nodes?
            }
            _nodes.add(this)
        }
    }

    fun baseDirectory(nodeId: Int): Path = filesystem.getPath("/nodes/$nodeId")

    /**
     * Asks every node in order to process any queued up inbound messages. This may in turn result in nodes
     * sending more messages to each other, thus, a typical usage is to call runNetwork with the [rounds]
     * parameter set to -1 (the default) which simply runs as many rounds as necessary to result in network
     * stability (no nodes sent any messages in the last round).
     */
    @JvmOverloads
    fun runNetwork(rounds: Int = -1) {
        check(!networkSendManuallyPumped)
        fun pumpAll() = messagingNetwork.endpoints.map { it.pumpReceive(false) }

        if (rounds == -1) {
            while (pumpAll().any { it != null }) {
            }
        } else {
            repeat(rounds) {
                pumpAll()
            }
        }
    }

    /**
     * A bundle that separates the generic user nodes and service-providing nodes. A real network might not be so
     * clearly separated, but this is convenient for testing.
     */
    data class BasketOfNodes(val partyNodes: List<MockNode>, val notaryNode: MockNode, val mapNode: MockNode)

    /**
     * Sets up a network with the requested number of nodes (defaulting to two), with one or more service nodes that
     * run a notary, network map, any oracles etc.
     */
    @JvmOverloads
    fun createSomeNodes(numPartyNodes: Int = 2, nodeFactory: Factory<*> = defaultFactory, notaryKeyPair: KeyPair? = DUMMY_NOTARY_KEY): BasketOfNodes {
        require(nodes.isEmpty())
        val notaryServiceInfo = ServiceInfo(SimpleNotaryService.type)
        val notaryOverride = if (notaryKeyPair != null)
            mapOf(Pair(notaryServiceInfo, notaryKeyPair))
        else
            null
        val mapNode = createNode(nodeFactory = nodeFactory, advertisedServices = ServiceInfo(NetworkMapService.type))
        val mapAddress = mapNode.network.myAddress
        val notaryNode = createNode(mapAddress, nodeFactory = nodeFactory, overrideServices = notaryOverride, advertisedServices = notaryServiceInfo)
        val nodes = ArrayList<MockNode>()
        repeat(numPartyNodes) {
            nodes += createPartyNode(mapAddress)
        }
        return BasketOfNodes(nodes, notaryNode, mapNode)
    }

    fun createNotaryNode(networkMapAddress: SingleMessageRecipient? = null,
                         legalName: X500Name? = null,
                         overrideServices: Map<ServiceInfo, KeyPair>? = null,
                         serviceName: X500Name? = null): MockNode {
        return createNode(networkMapAddress, legalName = legalName, overrideServices = overrideServices,
                advertisedServices = *arrayOf(ServiceInfo(NetworkMapService.type), ServiceInfo(ValidatingNotaryService.type, serviceName)))
    }

    fun createPartyNode(networkMapAddress: SingleMessageRecipient,
                        legalName: X500Name? = null,
                        overrideServices: Map<ServiceInfo, KeyPair>? = null): MockNode {
        return createNode(networkMapAddress, legalName = legalName, overrideServices = overrideServices)
    }

    @Suppress("unused") // This is used from the network visualiser tool.
    fun addressToNode(msgRecipient: MessageRecipients): MockNode {
        return when (msgRecipient) {
            is SingleMessageRecipient -> nodes.single { it.network.myAddress == msgRecipient }
            is InMemoryMessagingNetwork.ServiceHandle -> {
                nodes.filter { it.advertisedServices.any { it == msgRecipient.service.info } }.firstOrNull()
                        ?: throw IllegalArgumentException("Couldn't find node advertising service with info: ${msgRecipient.service.info} ")
            }
            else -> throw IllegalArgumentException("Method not implemented for different type of message recipients")
        }
    }

    fun startNodes() {
        require(nodes.isNotEmpty())
        nodes.forEach { if (!it.started) it.start() }
    }

    fun stopNodes() {
        nodes.forEach { if (it.started) it.stop() }
        if (initialiseSerialization) resetTestSerialization()
    }

    // Test method to block until all scheduled activity, active flows
    // and network activity has ceased.
    fun waitQuiescent() {
        busyLatch.await()
    }
}
