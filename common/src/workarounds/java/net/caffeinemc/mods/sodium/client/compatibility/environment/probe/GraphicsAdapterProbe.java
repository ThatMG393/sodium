package net.caffeinemc.mods.sodium.client.compatibility.environment.probe;

import net.caffeinemc.mods.sodium.client.compatibility.environment.OsUtils;
import net.caffeinemc.mods.sodium.client.platform.windows.api.d3dkmt.D3DKMT;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.util.ExecutingCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class GraphicsAdapterProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium-GraphicsAdapterProbe");

    private static final Set<String> LINUX_PCI_CLASSES = Set.of(
            "0x030000", // PCI_CLASS_DISPLAY_VGA
            "0x030001", // PCI_CLASS_DISPLAY_XGA
            "0x030200", // PCI_CLASS_DISPLAY_3D
            "0x038000"  // PCI_CLASS_DISPLAY_OTHER
    );

    private static List<? extends GraphicsAdapterInfo> ADAPTERS = List.of();

    public static void findAdapters() {
        LOGGER.info("Searching for graphics cards...");

        List<? extends GraphicsAdapterInfo> adapters;

        try {
            adapters = switch (OsUtils.getOs()) {
                case WIN -> findAdapters$Windows();
                case LINUX -> findAdapters$Linux();
                default -> null;
            };
        } catch (Exception e) {
            LOGGER.error("Failed to find graphics adapters!", e);
            return;
        }

        if (adapters == null) {
            // Not supported on this platform
            return;
        } else if (adapters.isEmpty()) {
            // Tried to search for adapters, but didn't find anything
            LOGGER.warn("Could not find any graphics adapters! Probably the device is not on a bus we can probe, or " +
                    "there are no devices supporting 3D acceleration.");
        } else {
            // Search returned some adapters
            for (var adapter : adapters) {
                LOGGER.info("Found graphics adapter: {}", adapter);
            }
        }

        ADAPTERS = adapters;
    }

    private static List<? extends GraphicsAdapterInfo> findAdapters$Windows() {
        return D3DKMT.findGraphicsAdapters();
    }

    // We rely on separate detection logic for Linux because Oshi fails to find GPUs without
    // display outputs, and we can also retrieve the driver version for NVIDIA GPUs this way.
    private static List<? extends GraphicsAdapterInfo> findAdapters$Linux() {
        var results = new ArrayList<GraphicsAdapterInfo>();

        try (var devices = Files.list(Path.of("/sys/bus/pci/devices/"))) {
            Iterable<Path> devicesIter = devices::iterator;

            for (var devicePath : devicesIter) {
                var deviceClass = Files.readString(devicePath.resolve("class")).trim();

                if (!LINUX_PCI_CLASSES.contains(deviceClass)) {
                    continue;
                }

                var pciVendorId = Files.readString(devicePath.resolve("vendor")).trim();
                var pciDeviceId = Files.readString(devicePath.resolve("device")).trim();

                var adapterVendor = GraphicsAdapterVendor.fromPciVendorId(pciVendorId);
                var adapterName = getPciDeviceName$Linux(pciVendorId, pciDeviceId);

                var info = new GraphicsAdapterInfo.LinuxPciAdapterInfo(adapterVendor, adapterName, pciVendorId, pciDeviceId);
                results.add(info);
            }
        } catch (IOException ignored) {}

        return results;
    }

    private static @NotNull String getPciDeviceName$Linux(String vendorId, String deviceId) {
        // The Linux kernel doesn't provide a way to get the device name, so we need to use lspci,
        // since it comes with a list of known device names mapped to device IDs.
        // See `man lspci` for more information

        // [<vendor>]:[<device>][:<class>[:<prog-if>]]
        var deviceFilter = vendorId.substring(2) + ":" + deviceId.substring(2);

        return ExecutingCommand
                .runNative("lspci -vmm -d " + deviceFilter)
                .stream()
                .filter(line -> line.startsWith("Device:"))
                .map(line -> line.substring("Device:".length()).trim())
                .findFirst()
                .orElse("unknown");
    }

    public static Collection<? extends GraphicsAdapterInfo> getAdapters() {
        if (ADAPTERS == null) {
            LOGGER.error("Graphics adapters not probed yet; returning an empty list.");
            return Collections.emptyList();
        }

        return ADAPTERS;
    }
}
