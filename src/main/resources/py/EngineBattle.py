import subprocess
import requests
import time
import os
import re

def find_latest_jar(directory_path):
    jar_files = [f for f in os.listdir(directory_path) if f.endswith('.jar') and re.match(r'chess-engine-\d+\.\d+\.\d+\.jar', f)]
    jar_files.sort(reverse=True)  # Sort the files by name in reverse, assuming newer versions have higher numbers
    return jar_files[0] if jar_files else None

def get_file_name_without_extension(file_path):
    base_name = os.path.basename(file_path)  # Get the file name from the path
    file_name_without_extension = os.path.splitext(base_name)[0]  # Remove the extension
    return file_name_without_extension

def start_java_process(jar_path, jar_port):
   with open(os.devnull, 'w') as devnull:
      return subprocess.Popen(['java', '-jar', jar_path, '--server.port=' + jar_port], stdout=devnull, stderr=devnull)

#def start_java_process(jar_path, jar_port):
    #return subprocess.Popen(['java', '-jar', jar_path, '--server.port=' + str(jar_port)])

# Function to make a move on a chess engine
def make_move(engine_url, from_pos, to_pos):
    #print(f"{engine_url}: {from_pos}:{to_pos}")
    response = requests.patch(f"{engine_url}/chess/figure/move/{from_pos}/{to_pos}")
    return response.json()  # Assuming the response is in JSON format

# Function to check if the server is running
def is_server_running(url):
    try:
        response = requests.get(url)
        return response.status_code == 200
    except requests.ConnectionError:
        return False
    
# Function to set time limit on a chess engine
def set_time_limit(engine_url, time_limit):
    response = requests.patch(f"{engine_url}/chess/autoplay/timelimit/{time_limit}")
    return response.status_code == 200

# Function to start autoplay for a given color on a chess engine
def start_autoplay(engine_url, ai_color):
    # Start autoplay with the AI's color
    response = requests.patch(f"{engine_url}/chess/autoplay/{ai_color}")
    return response.status_code == 200

# Function to get the last move from a chess engine
def get_last_move(engine_url):
    response = requests.get(f"{engine_url}/chess/autoplay/lastMove")
    if response.status_code == 200:
        #print(f"{engine_url}: {response.json()}")
        return response.json() 
    return None

# Function to check game state
def check_game_state(state):
    if state == "WHITE_WON":
        return "white"
    elif state == "BLACK_WON":
        return "black"
    elif state == "DRAW":
        return "draw"
    return None

# Function to reset the board on a chess engine
def reset_board(engine_url):
    response = requests.put(f"{engine_url}/chess/reset")
    return response.status_code == 200

target_directory = "target"
jar_file_name = find_latest_jar(target_directory)

# Paths to the JAR files and ports
jar1_path = "D:/Chess-Engines/v2/chess-engine-2.9.0.jar"

if jar_file_name:
    jar2_path = os.path.join(target_directory, jar_file_name)
    print("JAR Path:", jar2_path)
else:
    print("No JAR file found in target directory")

jar1_port = "8080"
jar2_port = "8082"

# Start the Java processes
engine1_process = start_java_process(jar1_path, jar1_port)
engine2_process = start_java_process(jar2_path, jar2_port)

# Give the servers some time to start
time.sleep(10)  # Adjust this time as needed
time_limit = 50

# URLs of the chess engine APIs
engine1_url = "http://localhost:" + jar1_port
engine2_url = "http://localhost:" + jar2_port

# Initialize win/draw counters
engine1_wins, engine2_wins, draws = 0, 0, 0

last_move_made_by_engine1 = None
last_move_made_by_engine2 = None

# Time limit for making a move (in seconds)
move_time_limit = 3

# Check if both servers are running
if not is_server_running(engine1_url):
    print(f"{engine1_url} is not running.")
    exit(1)

if not is_server_running(engine2_url):
    print(f"{engine2_url} is not running.")
    exit(1)

print("Both servers are running. Ready to play.")

# Main loop to play 1000 games
for game_number in range(1, 101):
    print(f"Starting game {game_number}")

    # Resetting last move time for each engine at the start of the game
    last_move_time_engine1 = time.time()
    last_move_time_engine2 = time.time()
    # Assign colors for this game
    color_engine1, color_engine2 = ("WHITE", "BLACK") if game_number % 2 == 1 else ("BLACK", "WHITE")

    # Set time limit and start autoplay for both engines
    if not (set_time_limit(engine1_url, time_limit) and set_time_limit(engine2_url, time_limit)):
        print(f"Failed to set time limits for game {game_number}")
        break

    if not (start_autoplay(engine1_url, color_engine1) and start_autoplay(engine2_url, color_engine2)):
        print(f"Failed to start autoplay for game {game_number}")
        break

    try:
        # Game loop
        while True:
            # Get move from engine 1
            last_move_engine1 = get_last_move(engine1_url)
            if last_move_engine1 != last_move_made_by_engine1:
                last_move_made_by_engine1 = last_move_engine1
                last_move_time_engine1 = time.time()  # Reset the timer as engine 1 made a move
                if last_move_engine1 and 'from' in last_move_engine1 and 'to' in last_move_engine1:
                    make_move(engine2_url, last_move_engine1['from'], last_move_engine1['to'])


            # Get move from engine 2
            last_move_engine2 = get_last_move(engine2_url)
            if last_move_engine2 != last_move_made_by_engine2:
                last_move_made_by_engine2 = last_move_engine2
                last_move_time_engine2 = time.time()  # Reset the timer as engine 2 made a move
                if last_move_engine2 and 'from' in last_move_engine2 and 'to' in last_move_engine2:
                    make_move(engine1_url, last_move_engine2['from'], last_move_engine2['to'])

            # Check if engine 1 has exceeded the move time limit
            if time.time() - last_move_time_engine1 > move_time_limit:
                engine2_wins += 1
                print(f"{get_file_name_without_extension(jar1_path)} failed to make a move in time.")
                break

            # Check if engine 2 has exceeded the move time limit
            if time.time() - last_move_time_engine2 > move_time_limit:
                engine1_wins += 1
                print(f"{get_file_name_without_extension(jar2_path)} failed to make a move in time.")
                break

            # Check game state
            game_state = None
            if last_move_engine1 and 'currentState' in last_move_engine1:
                game_state = check_game_state(last_move_engine1['currentState'])
            elif last_move_engine2 and 'currentState' in last_move_engine2:
                game_state = check_game_state(last_move_engine2['currentState'])

            if game_state:
                # Update counters based on game state and the color each engine played
                if color_engine1 == "WHITE":
                    if game_state == "white":
                        engine1_wins += 1
                    elif game_state == "black":
                        engine2_wins += 1
                else:
                    if game_state == "white":
                        engine2_wins += 1
                    elif game_state == "black":
                        engine1_wins += 1

                if game_state == "draw":
                    draws += 1
                
                break
    except KeyboardInterrupt:
        print("Game interrupted.")
        break
    # Output game result counters
    print(f"{get_file_name_without_extension(jar1_path)} wins: {engine1_wins}, {get_file_name_without_extension(jar2_path)} wins: {engine2_wins}, Draws: {draws}")
    # Reset both boards after a game
    if not (reset_board(engine1_url) and reset_board(engine2_url)):
        print(f"Failed to reset boards after game {game_number}")
        break



# Terminate the Java processes
engine1_process.terminate()
engine2_process.terminate()
engine1_process.wait()
engine2_process.wait()
print("Chess engines terminated.")
