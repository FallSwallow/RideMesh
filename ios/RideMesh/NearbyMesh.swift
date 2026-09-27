import Foundation
import NearbyConnections

final class NearbyMesh: NSObject {
    private let manager: ConnectionManager
    private let advertiser: Advertiser
    private let discoverer: Discoverer
    private let router: MeshRouter
    private let onStatus: (String) -> Void
    private let localContext: Data
    private let localIdentity: String
    private let localMediums: Set<Medium> = [.bluetooth, .ble, .wifiLAN, .wifiHotspot, .wifiDirect, .awdl]
    private var codes = [EndpointID: String]()
    private var connecting = Set<EndpointID>()
    private var connected = Set<EndpointID>()
    private var running = false
    private var scanTimer: Timer?
    private var stopScanTimer: Timer?

    init(router: MeshRouter, onStatus: @escaping (String) -> Void) {
        self.router = router
        self.onStatus = onStatus
        self.localIdentity = "I" + router.nodeID.hex
        self.localContext = Data("\(router.roomID)|\(Wire.protocolVersion)|\(localIdentity)".utf8)
        self.manager = ConnectionManager(serviceID: Wire.serviceID, strategy: .cluster)
        self.advertiser = Advertiser(connectionManager: manager)
        self.discoverer = Discoverer(connectionManager: manager)
        super.init()
        manager.delegate = self
        advertiser.delegate = self
        discoverer.delegate = self
    }

    func start() {
        guard !running else { return }
        running = true
        advertiser.startAdvertising(using: localContext, mediums: localMediums) { [weak self] error in
            if let error { self?.onStatus("發布失敗：\(error.localizedDescription)") }
        }
        scanCycle()
    }

    private func scanCycle() {
        guard running else { return }
        discoverer.startDiscovery(mediums: localMediums) { [weak self] error in
            if let error { self?.onStatus("搜尋失敗：\(error.localizedDescription)") }
        }
        stopScanTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
            self?.discoverer.stopDiscovery()
        }
        scanTimer = Timer.scheduledTimer(withTimeInterval: 12, repeats: false) { [weak self] _ in
            self?.scanCycle()
        }
    }

    func send(_ endpoint: EndpointID, data: Data) {
        guard connected.contains(endpoint) else { return }
        _ = manager.send(data, to: [endpoint])
    }

    func stop() {
        running = false
        stopScanTimer?.invalidate()
        scanTimer?.invalidate()
        discoverer.stopDiscovery()
        advertiser.stopAdvertising()
        for endpoint in connected {
            manager.disconnect(from: endpoint)
            router.disconnected(endpoint)
        }
        connected.removeAll()
        connecting.removeAll()
        codes.removeAll()
    }

    private func peerIdentity(_ context: Data) -> String? {
        guard let name = String(data: context, encoding: .utf8) else { return nil }
        let parts = name.split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count == 3, String(parts[0]) == router.roomID,
              String(parts[1]) == String(Wire.protocolVersion) else { return nil }
        let identity = String(parts[2])
        guard identity.count == 17, (identity.first == "A" || identity.first == "I"),
              identity.dropFirst().allSatisfy({ $0.isHexDigit }) else { return nil }
        return identity
    }
}

extension NearbyMesh: AdvertiserDelegate {
    func advertiser(_ advertiser: Advertiser, didReceiveConnectionRequestFrom endpointID: EndpointID,
                    with context: Data, connectionRequestHandler: @escaping (Bool) -> Void) {
        connectionRequestHandler(running && peerIdentity(context) != nil && connected.count < 4)
    }
}

extension NearbyMesh: DiscovererDelegate {
    func discoverer(_ discoverer: Discoverer, didFind endpointID: EndpointID, with context: Data) {
        guard running, let identity = peerIdentity(context), connected.count < 4,
              !connected.contains(endpointID), !connecting.contains(endpointID) else { return }
        // On mixed platforms, iOS requests Android. Same-platform peers use a stable tie-breaker.
        guard identity.first == "A" || localIdentity < identity else { return }
        connecting.insert(endpointID)
        discoverer.requestConnection(to: endpointID, using: localContext,
                                     mediums: localMediums) { [weak self] error in
            if error != nil { self?.connecting.remove(endpointID) }
        }
    }

    func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) {
        connecting.remove(endpointID)
    }
}

extension NearbyMesh: ConnectionManagerDelegate {
    func connectionManager(_ connectionManager: ConnectionManager,
                           didReceive verificationCode: String, from endpointID: EndpointID,
                           verificationHandler: @escaping (Bool) -> Void) {
        // The shared high-entropy group key is checked by MeshRouter before any audio is accepted.
        codes[endpointID] = verificationCode
        verificationHandler(true)
    }

    func connectionManager(_ connectionManager: ConnectionManager, didChangeTo state: ConnectionState,
                           for endpointID: EndpointID) {
        switch state {
        case .connected:
            connecting.remove(endpointID)
            connected.insert(endpointID)
            if let code = codes.removeValue(forKey: endpointID) { router.connected(endpointID, code: code) }
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
                guard let self, self.running, self.connected.contains(endpointID),
                      !self.router.isAuthorized(endpointID) else { return }
                self.manager.disconnect(from: endpointID)
            }
        case .disconnected, .rejected:
            connecting.remove(endpointID)
            connected.remove(endpointID)
            codes.removeValue(forKey: endpointID)
            router.disconnected(endpointID)
            if running { onStatus("鄰居斷線，正在重新搜尋") }
        case .connecting:
            break
        }
    }

    func connectionManager(_ connectionManager: ConnectionManager, didReceive data: Data,
                           withID payloadID: PayloadID, from endpointID: EndpointID) {
        router.receive(endpointID, data: data)
    }

    func connectionManager(_ connectionManager: ConnectionManager, didReceive stream: InputStream,
                           withID payloadID: PayloadID, from endpointID: EndpointID,
                           cancellationToken token: CancellationToken) {}

    func connectionManager(_ connectionManager: ConnectionManager,
                           didStartReceivingResourceWithID payloadID: PayloadID,
                           from endpointID: EndpointID, at localURL: URL, withName name: String,
                           cancellationToken token: CancellationToken) {}

    func connectionManager(_ connectionManager: ConnectionManager,
                           didReceiveTransferUpdate update: TransferUpdate,
                           from endpointID: EndpointID, forPayload payloadID: PayloadID) {}
}
