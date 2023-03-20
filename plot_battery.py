import os
import numpy as np
import matplotlib.pyplot as plt
import statistics

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
        battery_percent_dropped = (battery_level[0] - battery_level[-1]) * 100 / battery_scale[0]
        print("{}: Battery level: {}%: {}/{} -> {}/{}".format(label,
                                                              battery_percent_dropped,
                                                              battery_level[0], battery_scale[0],
                                                              battery_level[-1], battery_scale[0]))

        voltage_y = np.asarray(voltage_y) / 1000.
        # Compensate for different Battery Manager implementation
        current_y = np.abs(np.asarray(current_y) / 1000000.)
        voltage_t = np.asarray(voltage_t) / 1000.
        current_t = np.asarray(current_t) / 1000.

        fig, ax = plt.subplots()
        ax.plot(current_t, current_y, color="red", marker=".")
        ax.set_xlabel("time /s", fontsize=12)
        ax.set_ylabel("current /A", color="red", fontsize=12)
        ax.set_ylim((0, 1))

        power_y = []
        voltage_y2 = []
        vi = 0
        for ci, ts in enumerate(current_t):
            while vi + 1 < len(voltage_t) and voltage_t[vi + 1] <= ts:
                vi += 1
            power_y.append(voltage_y[vi] * current_y[ci])
            voltage_y2.append(voltage_y[vi])

        ax2 = ax.twinx()
        ax2.plot(current_t, voltage_y2, color="blue")
        ax2.set_ylabel("voltage /V", color="blue", fontsize=12)
        ax2.set_ylim((0, 5))
        plt.title(label)
        plt.show()
        fig.savefig(output_path, format='jpeg', dpi=100, bbox_inches='tight')

        return label, current_t, power_y, total_time, total_images, unique_images, battery_percent_dropped


def compare_power(profiles, output_file):
    output_path = os.path.join(OUTPUT_FOLDER, output_file)
    fig, ax = plt.subplots()
    ax.set_xlabel("time /s", fontsize=12)
    ax.set_ylabel("power /W", fontsize=12)
    ax.set_ylim((0, 4))

    profile_records = []
    profile_data = []
    for prof in profiles:
        print(prof[0] + ":")
        ax.plot(prof[1], prof[2], marker=".", label=prof[0])

        if prof[5] is not None:
            print("Unique images: {}".format(prof[5]))
            print("Removal rate: {:.2%}".format(1. - prof[5] / prof[4]))

        total_time = prof[3] / 1000.
        total_frame = prof[4]
        proc_fps = prof[4] * 1000. / prof[3]
        average_power = np.sum(prof[2]) / len(prof[2])
        battery_percent_dropped = prof[6]
        total_energy = average_power * prof[3] / 1000.
        energy_per_frame = total_energy / prof[4]
        print("Total time (s): {}".format(total_time))
        print("Total Input Frame: {}".format(total_frame))
        print("Processing FPS: {}".format(proc_fps))
        print("Average power (W): {}".format(average_power))
        print("Battery level dropped: {}%".format(battery_percent_dropped))
        print("Total energy (J): {}".format(total_energy))
        print("Energy per frame (J): {}".format(energy_per_frame))
        profile_record = [total_time, total_frame, proc_fps, 0, 0, 0, battery_percent_dropped, total_energy]
        print((total_time, total_frame, proc_fps, 0, 0,
               "{} [{}, {}]".format(statistics.mean(prof[2]), len(prof[2]), statistics.stdev(prof[2])),
               battery_percent_dropped, total_energy))
        profile_records.append(profile_record)
        profile_data += prof[2]
        print()

    agg_record_mean = np.array(profile_records).mean(axis=0)
    agg_record_stddev = np.array(profile_records).std(axis=0)
    print("Agg: ")
    for i in [0, 1, 2]:
        print("{:.2f}({:.2f})".format(agg_record_mean[i], agg_record_stddev[i]))
    print("{:.2f}({:.2f})".format(statistics.mean(profile_data), statistics.stdev(profile_data)))
    for i in [-2, -1]:
        print("{:.2f}({:.2f})".format(agg_record_mean[i], agg_record_stddev[i]))

    plt.title("Power Comparison")
    plt.legend()
    plt.show()
    fig.savefig(output_path, format='jpeg', dpi=100, bbox_inches='tight')


if __name__ == "__main__":
    prof_1 = draw("gg_idle.txt", "gg_idle.jpg", "gg_idle")
    prof_list = [prof_1]
    print()
    compare_power(prof_list, "power_comparison.jpg")
