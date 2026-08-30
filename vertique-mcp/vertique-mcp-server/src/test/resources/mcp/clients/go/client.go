// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

const (
	anonymousScenario     = "shouldDiscoverListAndCallAsAnonymous"
	bearerScenario        = "shouldCallTheRestrictedToolAsBearerAlice"
	invalidBearerScenario = "shouldFailInvalidBearerWithoutAnonymousDowngrade"
	publicTool            = "interop.public"
	restrictedTool        = "interop.restricted"
)

type report struct {
	Scenario                  string   `json:"scenario"`
	NegotiatedProtocolVersion string   `json:"negotiatedProtocolVersion,omitempty"`
	ToolNames                 []string `json:"toolNames,omitempty"`
	CalledTool                string   `json:"calledTool,omitempty"`
	ResultText                string   `json:"resultText,omitempty"`
	ResultIsError             bool     `json:"resultIsError,omitempty"`
	Failed                    bool     `json:"failed,omitempty"`
	ErrorMessage              string   `json:"errorMessage,omitempty"`
}

func main() {
	serverURL := flag.String("url", "", "MCP server URL")
	scenario := flag.String("scenario", "", "interop scenario")
	flag.Parse()
	if *serverURL == "" || *scenario == "" {
		fmt.Fprintln(os.Stderr, "required arguments: --url <url> --scenario <scenario>")
		os.Exit(2)
	}

	result, err := run(*serverURL, *scenario)
	if err != nil {
		if *scenario != invalidBearerScenario {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		result = report{Scenario: *scenario, Failed: true, ErrorMessage: err.Error()}
	} else if *scenario == invalidBearerScenario {
		fmt.Fprintln(os.Stderr, "invalid bearer scenario unexpectedly connected")
		os.Exit(1)
	}

	encoded, err := json.Marshal(result)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Printf("T029_RESULT=%s\n", encoded)
}

func run(serverURL, scenario string) (report, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	httpClient := &http.Client{Timeout: 30 * time.Second}
	if token := bearerToken(scenario); token != "" {
		httpClient.Transport = bearerTransport{token: token, base: http.DefaultTransport}
	}
	client := mcp.NewClient(
		&mcp.Implementation{Name: "vertique-t029-client", Version: "1.0.0"},
		&mcp.ClientOptions{Capabilities: &mcp.ClientCapabilities{}},
	)
	session, err := client.Connect(ctx, &mcp.StreamableClientTransport{
		Endpoint:             serverURL,
		HTTPClient:           httpClient,
		DisableStandaloneSSE: true,
	}, nil)
	if err != nil {
		return report{}, fmt.Errorf("connect: %w", err)
	}
	defer session.Close()

	initialized := session.InitializeResult()
	if initialized == nil {
		return report{}, errors.New("client did not retain discovery result")
	}
	listed, err := session.ListTools(ctx, nil)
	if err != nil {
		return report{}, fmt.Errorf("list tools: %w", err)
	}
	toolNames := make([]string, 0, len(listed.Tools))
	for _, tool := range listed.Tools {
		toolNames = append(toolNames, tool.Name)
	}
	calledTool := publicTool
	if scenario == bearerScenario {
		calledTool = restrictedTool
	}
	if scenario != anonymousScenario && scenario != bearerScenario {
		return report{}, fmt.Errorf("scenario unexpectedly connected: %s", scenario)
	}
	called, err := session.CallTool(ctx, &mcp.CallToolParams{Name: calledTool, Arguments: map[string]any{}})
	if err != nil {
		return report{}, fmt.Errorf("call tool: %w", err)
	}
	if len(called.Content) != 1 {
		return report{}, fmt.Errorf("expected one result content item, got %d", len(called.Content))
	}
	text, ok := called.Content[0].(*mcp.TextContent)
	if !ok {
		return report{}, fmt.Errorf("expected text result content, got %T", called.Content[0])
	}

	return report{
		Scenario:                  scenario,
		NegotiatedProtocolVersion: initialized.ProtocolVersion,
		ToolNames:                 toolNames,
		CalledTool:                calledTool,
		ResultText:                text.Text,
		ResultIsError:             called.IsError,
	}, nil
}

func bearerToken(scenario string) string {
	switch scenario {
	case bearerScenario:
		return "alice"
	case invalidBearerScenario:
		return "invalid"
	default:
		return ""
	}
}

type bearerTransport struct {
	token string
	base  http.RoundTripper
}

func (t bearerTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	copy := request.Clone(request.Context())
	copy.Header.Set("Authorization", "Bearer "+t.token)
	return t.base.RoundTrip(copy)
}
