import os
import numpy as np
import matplotlib.pyplot as plt

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
LOG_FOLDER = os.path.join(PROJECT_DIR, "logs")
OUTPUT_FOLDER = os.path.join(PROJECT_DIR, "plots")


def draw(log_file, output_file, label):
    output_path = os.path.join(OUTPUT_FOLDER, output_file)
    with open(os.path.join(LOG_FOLDER, log_file), "r") as fi:
        lines = fi.readlines()
        timestamps = []
        events = []
        start_time = 0
        stop_time = 0
        total_images = None
        unique_images = None
        for line in lines:
            if "Time: " in line:
                fields = line.split("\t")
                timestamps.append(int(fields[0].split(" ")[-1]))
                events.append(fields[1].strip())
            elif "Total Input Frames: " in line:
                total_images = int(line.split(" ")[-1].strip())
            elif "Unique Images: " in line:
                unique_images = int(line.split(" ")[-1].strip())
            elif "Start: " in line:
                start_time = int(line.split(" ")[-1].strip())
            elif "Stop: " in line:
                stop_time = int(line.split(" ")[-1].strip())
        total_time = stop_time - start_time

        voltage_t = []
        current_t = []
        voltage_y = []
        current_y = []
        battery_level = []
        battery_scale = []
        for i, event in enumerate(events):
            if "Battery voltage" in event:
                voltage_t.append(timestamps[i] - start_time)
                voltage_y.append(int(event.split(" ")[3]))
                battery_level.append(int(event.split(" ")[-1].split("/")[0]))
                battery_scale.append(int(event.split(" ")[-1].split("/")[1]))
            if timestamps[i] < start_time or timestamps[i] >= stop_time:
                continue
            if "Current" in event:
                current_t.append(timestamps[i] - start_time)
                current_y.append(int(event.split(" ")[-1]))

        assert all(battery_level[i] >= battery_level[i + 1] for i in range(len(battery_level) - 1))
        assert all(scale == battery_scale[0] for scale in battery_scale)
        print("{}: Battery level: {}/{} -> {}/{}".format(label, battery_level[0], battery_scale[0],
                                                         battery_level[-1], battery_scale[0]))

        voltage_y = np.asarray(voltage_y) / 1000.
        current_y = np.asarray(current_y) / 1000000.
        voltage_t = np.asarray(voltage_t) / 1000.
        current_t = np.asarray(current_t) / 1000.

        fig, ax = plt.subplots()
        ax.plot(current_t, current_y, color="red", marker=".")
        ax.set_xlabel("time /s", fontsize=12)
        ax.set_ylabel("current /A", color="red", fontsize=12)
        ax.set_ylim((0, 1))

        ax2 = ax.twinx()
        ax2.plot(voltage_t, voltage_y, color="blue", marker=".")
        ax2.set_ylabel("voltage /V", color="blue", fontsize=12)
        ax2.set_ylim((0, 5))
        plt.title(label)
        plt.show()
        fig.savefig(output_path, format='jpeg', dpi=100, bbox_inches='tight')

        power_y = []
        vi = 0
        for ci, ts in enumerate(current_t):
            while vi + 1 < len(voltage_t) and voltage_t[vi + 1] <= ts:
                vi += 1
            power_y.append(voltage_y[vi] * current_y[ci])
        return label, current_t, power_y, total_time, total_images, unique_images


def compare_power(profiles, output_file):
    output_path = os.path.join(OUTPUT_FOLDER, output_file)
    fig, ax = plt.subplots()
    ax.set_xlabel("time /s", fontsize=12)
    ax.set_ylabel("power /W", fontsize=12)
    ax.set_ylim((0, 4))

    for prof in profiles:
        print(prof[0] + ":")
        ax.plot(prof[1], prof[2], label=prof[0], linewidth=2)
        time_per_frame = prof[3] / prof[4]
        print("Total Input Frame: {}".format(prof[4]))
        if prof[5] is not None:
            print("Unique images: {}".format(prof[5]))
            print("Removal rate: {:.2%}".format(1. - prof[5] / prof[4]))
        print("Total time (s): {}".format(prof[3] / 1000.))
        print("Time per frame (ms): {:.2f}".format(time_per_frame))
        average_power = np.sum(prof[2]) / len(prof[2])
        print("Average power (W): {:.2f}".format(average_power))
        energy_per_frame = average_power * time_per_frame / 1000.
        print("Energy per frame (J): {:.4f}".format(energy_per_frame))
        total_energy = average_power * prof[3] / 1000.
        print("Total energy (J): {:.2f}".format(total_energy))
        print()

    plt.title("Power Comparison")
    plt.legend()
    plt.show()
    fig.savefig(output_path, format='jpeg', dpi=100, bbox_inches='tight')


if __name__ == "__main__":
    prof_1 = draw("glass_idle.txt", "glass_idle.jpg", "Google Glass - Baseline")
    prof_2 = draw("glass_no_gating.txt", "glass_no_gating.jpg", "Google Glass - No Gating")
    prof_3 = draw("glass_cloudlet_thumbsup.txt", "glass_cloudlet_thumbsup.jpg",
                  "Google Glass - Cloudlet Thumbs-up Detection")
    prof_4 = draw("glass_thumbsup.txt", "glass_thumbsup.jpg", "Google Glass - On-device Thumbs-up Detection")
    prof_5 = draw("glass_toggle_switch.txt", "glass_toggle_switch.jpg", "Google Glass - Toggle Switch")
    prof_6 = draw("glass_asr_gating.txt", "glass_asr_gating.jpg", "Google Glass - On-device ASR Gating")
    prof_list = [prof_1, prof_2, prof_3, prof_4, prof_5, prof_6]
    compare_power(prof_list, "power_comparison.jpg")
