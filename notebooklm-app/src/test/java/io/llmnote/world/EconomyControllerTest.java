package io.llmnote.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class EconomyControllerTest {

    @Mock TownEconomyDailyRepository economyDailyRepo;
    @Mock PlaceRevenueRepository placeRevenueRepo;
    @Mock AgentEmployeeRepository employeeRepo;
    @Mock AgentTransactionRepository txRepo;
    @Mock TownStockRepository stockRepo;
    @Mock StockDailyRepository stockDailyRepo;
    @Mock StockHoldingRepository holdingRepo;
    @InjectMocks EconomyController controller;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void summary_emptyDb_returnsEmptyStateNot500() throws Exception {
        when(economyDailyRepo.findTopByOrderBySimDateDesc()).thenReturn(Optional.empty());
        when(economyDailyRepo.findTop30ByOrderBySimDateDesc()).thenReturn(List.of());

        mvc.perform(get("/api/world/economy/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latest").doesNotExist())
                .andExpect(jsonPath("$.series").isArray());
    }

    @Test
    void summary_withSnapshot_returnsTotals() throws Exception {
        TownEconomyDaily snap = new TownEconomyDaily();
        snap.setSimDate(LocalDate.of(2026, 7, 15));
        snap.setTotalCoins(9999L);
        when(economyDailyRepo.findTopByOrderBySimDateDesc()).thenReturn(Optional.of(snap));
        when(economyDailyRepo.findTop30ByOrderBySimDateDesc()).thenReturn(List.of(snap));

        mvc.perform(get("/api/world/economy/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latest.totalCoins").value(9999));
    }

    @Test
    void rich_emptyDb_returnsEmptyArray() throws Exception {
        when(employeeRepo.findByStatusOrderByIdAsc("active")).thenReturn(List.of());
        mvc.perform(get("/api/world/economy/rich"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void income_unknownAgent_returnsFoundFalse() throws Exception {
        when(employeeRepo.findById(999L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/world/agents/999/income"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));
    }

    @Test
    void stocks_emptyDb_returnsEmptyArray() throws Exception {
        when(stockRepo.findAll()).thenReturn(List.of());
        mvc.perform(get("/api/world/stocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void holdings_emptyDb_returnsEmptyArray() throws Exception {
        when(holdingRepo.findByAgentId(1L)).thenReturn(List.of());
        mvc.perform(get("/api/world/agents/1/holdings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
